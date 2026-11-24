package com.inqwise.context;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.data.FlowIdService.CodecProfile;
import com.inqwise.context.extension.StringListContext;
import com.inqwise.context.extension.StringMapContext;
import com.inqwise.context.extension.StringSetContext;
import com.inqwise.context.proxy.ClusterFlowService;
import com.inqwise.context.spi.ContextChainDataService;
import com.inqwise.context.spi.ContextItemFactory;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceProxyBuilder;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.inqwise.context.ClusterTestSupport.await;
import static com.inqwise.context.ClusterTestSupport.clusterConfig;
import static com.inqwise.context.ClusterTestSupport.startNode;
import static com.inqwise.context.ContextCollectionsClusterNode.PRIVATE;
import static com.inqwise.context.extension.StringListContext.STEPS;
import static com.inqwise.context.extension.StringMapContext.VALUES;
import static com.inqwise.context.extension.StringSetContext.LABELS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(30)
class ForkedClusterCollectionsTransportTest {
    private enum Call {
        EVENT_BUS, SERVICE_PROXY, SERVICE_FAILURE
    }

    @TempDir
    static Path directory;

    private Vertx sender;
    private HazelcastClusterManager manager;
    private Process child;
    private Path log;
    private String address;

    @BeforeAll
    @Timeout(90)
    void startCluster() throws Exception {
        String cluster = "collections-" + UUID.randomUUID();
        address = "collections-" + UUID.randomUUID();
        manager = new HazelcastClusterManager(clusterConfig(cluster, null));
        sender = await(startNode(manager));
        EventBusTransportHandler transport = new EventBusTransportHandler(() -> List.of(
            new FlowIdService(CodecProfile.LEGACY), new StringMapContext.Codec(),
            new StringSetContext.Codec(), new StringListContext.Codec()));
        sender.eventBus().addOutboundInterceptor(transport::handleOutbound);
        sender.eventBus().addInboundInterceptor(transport::handleInbound);
        Path runtime = directory.resolve("runtime");
        Path services = runtime.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(ContextChainDataService.class.getName()),
            StringMapContext.Codec.class.getName() + "\n" + StringSetContext.Codec.class.getName() + "\n"
                + StringListContext.Codec.class.getName() + "\n");
        Files.writeString(services.resolve(ContextItemFactory.class.getName()),
            StringMapContext.Factory.class.getName() + "\n" + StringSetContext.Factory.class.getName() + "\n"
                + StringListContext.Factory.class.getName() + "\n");
        String seed = "127.0.0.1:" + manager.getHazelcastInstance().getCluster().getLocalMember().getAddress().getPort();
        Path ready = directory.resolve("receiver.ready");
        log = directory.resolve("receiver.log");
        child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-D" + FlowIdService.CODEC_PROPERTY + "=json", "-cp", runtime + File.pathSeparator
                + System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            ContextCollectionsClusterNode.class.getName(), cluster, seed, address, ready.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(ready)) {
            if (!child.isAlive() || System.nanoTime() >= deadline) {
                throw new AssertionError("Collection receiver did not become ready:\n" + Files.readString(log));
            }
            Thread.sleep(25);
        }
        assertEquals(Long.toString(child.pid()), Files.readString(ready));
        assertNotEquals(ProcessHandle.current().pid(), child.pid());
        assertEquals(2, manager.getHazelcastInstance().getCluster().getMembers().size());
    }

    @AfterAll
    @Timeout(90)
    void stopCluster() throws Exception {
        try {
            if (child != null) {
                try {
                    child.getOutputStream().close();
                    assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Collection receiver did not stop");
                    assertEquals(0, child.exitValue(), () -> {
                        try {
                            return Files.readString(log);
                        } catch (Exception error) {
                            return error.toString();
                        }
                    });
                } finally {
                    if (child.isAlive()) {
                        child.destroyForcibly();
                        assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Cannot terminate collection receiver");
                    }
                }
            }
        } finally {
            try {
                if (sender != null) {
                    await(sender.close());
                }
            } finally {
                if (manager != null && manager.getHazelcastInstance() != null
                        && manager.getHazelcastInstance().getLifecycleService().isRunning()) {
                    manager.getHazelcastInstance().shutdown();
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void eventBus_twoJvms_transportsCollectionSnapshotsAndRemovals(ThreadingModel model, String state, boolean removal)
            throws Exception {
        String deployment = await(deployScenario(model, state, removal, null, 1, Call.EVENT_BUS));
        await(sender.undeploy(deployment));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void serviceProxy_twoJvms_transportsCollectionSnapshotsAndRemovals(ThreadingModel model, String state, boolean removal)
            throws Exception {
        String deployment = await(deployScenario(model, state, removal, null, 1, Call.SERVICE_PROXY));
        await(sender.undeploy(deployment));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void serviceProxy_failedReply_importsCollectionChangesAndRetainsCallerFlowId(
            ThreadingModel model, String state, boolean removal) throws Exception {
        String deployment = await(deployScenario(model, state, removal, null, 1, Call.SERVICE_FAILURE));
        await(sender.undeploy(deployment));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void serviceProxy_lateClusteredReply_cannotChangeExpiredCallerOrNextCall(
            ThreadingModel model, String state, boolean remoteRemoval) throws Exception {
        String deployment = await(sender.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                ContextInternal caller = ContextInternal.current();
                String token = UUID.randomUUID().toString();
                String currentToken = "pending-" + token;
                FlowIdContext.put(FlowId.builder().withId(token).build());
                ContextCollectionsClusterNode.prepare(state, token);
                PRIVATE.replace(Map.of("caller", token));
                var privateItem = PRIVATE.slot().require();
                ClusterFlowService slow = new ServiceProxyBuilder(vertx).setAddress(address + "-proxy")
                    .setOptions(new DeliveryOptions().setSendTimeout(500)).build(ClusterFlowService.class);
                Future<String> pending = slow.process(new JsonObject().put("token", token).put("state", state)
                    .put("remove", remoteRemoval).put("hold", true).encode());
                return waitForControl(vertx, token, false, System.nanoTime() + TimeUnit.SECONDS.toNanos(3)).compose(entered -> {
                    ContextCollectionsClusterNode.prepare(state, currentToken);
                    FlowId current = remoteRemoval ? null : FlowId.builder().withId(currentToken).build();
                    if (remoteRemoval) {
                        FlowIdContext.remove();
                    } else {
                        FlowIdContext.put(current);
                    }
                    var currentMap = VALUES.slot().get();
                    var currentSet = LABELS.slot().get();
                    var currentList = STEPS.slot().get();
                    Runnable assertCaller = () -> {
                        assertSame(caller, ContextInternal.current());
                        assertEquals(model, caller.threadingModel());
                        assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                        assertSame(current, FlowIdContext.get());
                        assertEquals(remoteRemoval, DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                        assertSame(privateItem, PRIVATE.slot().require());
                        ContextCollectionsClusterNode.assertState(state, currentToken);
                    };
                    Runnable assertSnapshots = () -> {
                        assertCaller.run();
                        assertSame(currentMap, VALUES.slot().get());
                        assertSame(currentSet, LABELS.slot().get());
                        assertSame(currentList, STEPS.slot().get());
                    };
                    return pending.transform(result -> {
                        assertTrue(result.failed());
                        assertEquals(ReplyFailure.TIMEOUT, assertInstanceOf(ReplyException.class, result.cause()).failureType());
                        assertSnapshots.run();
                        return control(vertx, "release", token).compose(released -> {
                            assertTrue(released.getBoolean("released"));
                            assertSnapshots.run();
                            return waitForControl(vertx, token, true, System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
                        }).compose(dispatched -> {
                            assertLateHeaders(dispatched.getJsonObject("headers"), token, remoteRemoval);
                            assertSnapshots.run();
                            ClusterFlowService followup = new ServiceProxyBuilder(vertx).setAddress(address + "-proxy")
                                .setOptions(new DeliveryOptions().setSendTimeout(5_000)).build(ClusterFlowService.class);
                            return followup.process(new JsonObject().put("inspect", true).put("token", token)
                                .put("state", state).put("value", currentToken).put("flow", currentToken)
                                .put("flowRemoved", remoteRemoval).encode());
                        }).compose(text -> {
                            JsonObject report = new JsonObject(text);
                            assertEquals(child.pid(), report.getLong("pid"));
                            assertEquals(token, report.getString("token"));
                            assertCaller.run();
                            var returnedMap = VALUES.slot().get();
                            var returnedSet = LABELS.slot().get();
                            var returnedList = STEPS.slot().get();
                            return vertx.timer(1).map(tick -> {
                                assertCaller.run();
                                assertSame(returnedMap, VALUES.slot().get());
                                assertSame(returnedSet, LABELS.slot().get());
                                assertSame(returnedList, STEPS.slot().get());
                                return tick;
                            });
                        });
                    });
                });
            }
        }, new DeploymentOptions().setThreadingModel(model)));
        await(sender.undeploy(deployment));
    }

    private Future<JsonObject> control(Vertx vertx, String operation, String token) {
        return vertx.eventBus().<JsonObject>request(address + "-control",
            new JsonObject().put("operation", operation).put("token", token),
            new DeliveryOptions().setSendTimeout(5_000)).map(reply -> {
                for (String key : List.of("flow_id", VALUES.slot().key(), LABELS.slot().key(),
                        STEPS.slot().key(), PRIVATE.slot().key())) {
                    assertNull(reply.headers().get(key), "Control reply exported metadata: " + key);
                }
                assertEquals(child.pid(), reply.body().getLong("pid"));
                return reply.body();
            });
    }

    private Future<JsonObject> waitForControl(Vertx vertx, String token, boolean dispatched, long deadline) {
        return control(vertx, "status", token).compose(report -> {
            if (report.getString("failure") != null) {
                return Future.failedFuture("Remote held reply failed: " + report.getString("failure"));
            }
            if (dispatched ? report.getJsonObject("headers") != null : report.getBoolean("entered")) {
                return Future.succeededFuture(report);
            }
            if (System.nanoTime() >= deadline) {
                return Future.failedFuture("Clustered collection reply did not reach " + (dispatched ? "dispatch" : "arrival"));
            }
            return vertx.timer(2).compose(ignored -> waitForControl(vertx, token, dispatched, deadline));
        });
    }

    private static void assertLateHeaders(JsonObject headers, String token, boolean removal) {
        assertNull(headers.getString(PRIVATE.slot().key()));
        var map = new StringMapContext.Codec();
        var set = new StringSetContext.Codec();
        var list = new StringListContext.Codec();
        if (removal) {
            assertTrue(map.isRemoval(headers.getString(VALUES.slot().key())));
            assertTrue(set.isRemoval(headers.getString(LABELS.slot().key())));
            assertTrue(list.isRemoval(headers.getString(STEPS.slot().key())));
        } else {
            assertEquals(Map.of("value", "returned-" + token, "edited", "entry"),
                map.unmarshalFormString(headers.getString(VALUES.slot().key())).snapshot());
            assertEquals(Set.of("returned-" + token, "edited"),
                set.unmarshalFormString(headers.getString(LABELS.slot().key())).snapshot());
            assertEquals(List.of("returned-" + token, "edited", "edited"),
                list.unmarshalFormString(headers.getString(STEPS.slot().key())).snapshot());
        }
        assertEquals("remote-return", ((FlowId) new FlowIdService().unmarshalFormString(headers.getString("flow_id"))).getId());
    }

    @ParameterizedTest
    @MethodSource("overlapScenarios")
    void eventBus_overlappingRequests_isolateCollectionsAcrossTwoJvms(ThreadingModel model, int repetition)
            throws Exception {
        runBatch(model, repetition, List.of(Call.EVENT_BUS));
    }

    @ParameterizedTest
    @MethodSource("overlapScenarios")
    void serviceProxy_overlappingRequests_isolateCollectionsAcrossTwoJvms(ThreadingModel model, int repetition)
            throws Exception {
        runBatch(model, repetition, List.of(Call.SERVICE_PROXY));
    }

    @ParameterizedTest
    @MethodSource("overlapScenarios")
    void serviceProxy_mixedSuccessAndFailureReplies_isolateConcurrentCollectionChanges(
            ThreadingModel model, int repetition) throws Exception {
        runBatch(model, repetition, List.of(Call.SERVICE_PROXY, Call.SERVICE_FAILURE));
    }

    private void runBatch(ThreadingModel model, int repetition, List<Call> calls) throws Exception {
        String batch = repetition + "-" + UUID.randomUUID();
        int peers = 8 * calls.size();
        List<Future<String>> pending = new ArrayList<>();
        List<String> deployed = new CopyOnWriteArrayList<>();
        try {
            for (Call call : calls) {
                for (String state : List.of("value", "empty", "removed", "absent")) {
                    for (boolean removal : List.of(false, true)) {
                        pending.add(deployScenario(model, state, removal, batch, peers, call).andThen(result -> {
                            if (result.succeeded()) {
                                deployed.add(result.result());
                            }
                        }));
                    }
                }
            }
            await(Future.join(pending));
            assertEquals(peers, deployed.size());
        } finally {
            await(Future.join(deployed.stream().map(sender::undeploy).toList()));
        }
    }

    private Future<String> deployScenario(ThreadingModel model, String state, boolean removal, String batch, int peers,
            Call call) {
        return sender.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                ContextInternal caller = ContextInternal.current();
                String token = UUID.randomUUID() + "-\u0394";
                FlowId original = FlowId.builder().withId(token).build();
                FlowId current = FlowId.builder().withId("current-" + token).build();
                FlowIdContext.put(original);
                ContextCollectionsClusterNode.prepare(state, token);
                PRIVATE.replace(Map.of("caller", token));
                var privateItem = PRIVATE.slot().require();
                JsonObject command = new JsonObject().put("token", token).put("state", state).put("remove", removal)
                    .put("batch", batch).put("size", peers).put("fail", call == Call.SERVICE_FAILURE);
                Future<JsonObject> pending;
                if (call != Call.EVENT_BUS) {
                    ClusterFlowService service = new ServiceProxyBuilder(vertx).setAddress(address + "-proxy")
                        .setOptions(new DeliveryOptions().setSendTimeout(5_000)).build(ClusterFlowService.class);
                    pending = service.process(command.encode()).transform(result -> {
                        assertSame(caller, ContextInternal.current());
                        if (call == Call.SERVICE_FAILURE) {
                            assertTrue(result.failed(), "Expected controlled service failure");
                            ReplyException failure = assertInstanceOf(ReplyException.class, result.cause());
                            assertEquals(ReplyFailure.RECIPIENT_FAILURE, failure.failureType());
                            assertEquals(73, failure.failureCode());
                            return Future.succeededFuture(new JsonObject(failure.getMessage()));
                        }
                        return result.succeeded() ? Future.succeededFuture(new JsonObject(result.result()))
                            : Future.failedFuture(result.cause());
                    });
                } else {
                    pending = vertx.eventBus().<JsonObject>request(address, command,
                        new DeliveryOptions().setSendTimeout(5_000)).map(reply -> {
                            assertNull(reply.headers().get(PRIVATE.slot().key()));
                            return reply.body();
                        });
                }
                ContextCollectionsClusterNode.replace("pending");
                var pendingMap = VALUES.slot().require();
                var pendingSet = LABELS.slot().require();
                var pendingList = STEPS.slot().require();
                if (removal) {
                    FlowIdContext.remove();
                } else {
                    FlowIdContext.put(current);
                }
                return pending.compose(reply -> {
                    assertSame(caller, ContextInternal.current());
                    assertEquals(child.pid(), reply.getLong("pid"));
                    assertEquals(token, reply.getString("token"));
                    assertEquals(peers, reply.getInteger("peersSeen"));
                    assertSame(removal ? null : current, FlowIdContext.get());
                    assertEquals(removal, DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                    assertSame(privateItem, PRIVATE.slot().require());
                    if (removal) {
                        ContextCollectionsClusterNode.assertState("removed", token);
                    } else {
                        assertEquals(Map.of("value", "returned-" + token, "edited", "entry"), VALUES.snapshot());
                        assertEquals(Set.of("returned-" + token, "edited"), LABELS.snapshot());
                        assertEquals(List.of("returned-" + token, "edited", "edited"), STEPS.snapshot());
                    }
                    assertNotSame(pendingMap, VALUES.slot().get());
                    assertNotSame(pendingSet, LABELS.slot().get());
                    assertNotSame(pendingList, STEPS.slot().get());
                    assertEquals(Map.of("value", "pending"), pendingMap.snapshot());
                    assertEquals(Set.of("pending"), pendingSet.snapshot());
                    assertEquals(List.of("pending", "pending"), pendingList.snapshot());
                    var returnedMap = VALUES.slot().get();
                    var returnedSet = LABELS.slot().get();
                    var returnedList = STEPS.slot().get();
                    return vertx.timer(1).map(tick -> {
                        assertSame(caller, ContextInternal.current());
                        assertEquals(model, caller.threadingModel());
                        assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                        assertSame(removal ? null : current, FlowIdContext.get());
                        assertSame(returnedMap, VALUES.slot().get());
                        assertSame(returnedSet, LABELS.slot().get());
                        assertSame(returnedList, STEPS.slot().get());
                        return tick;
                    });
                });
            }
        }, new DeploymentOptions().setThreadingModel(model));
    }

    private static Stream<Arguments> scenarios() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of("value", "empty", "removed", "absent")
                .flatMap(state -> Stream.of(false, true).map(removal -> Arguments.of(model, state, removal))));
    }

    private static Stream<Arguments> overlapScenarios() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of(1, 2).map(repetition -> Arguments.of(model, repetition)));
    }
}
