package com.inqwise.context;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.data.FlowIdService.CodecProfile;
import com.inqwise.context.extension.StringListContext;
import com.inqwise.context.extension.StringMapContext;
import com.inqwise.context.extension.StringSetContext;
import com.inqwise.context.spi.ContextChainDataService;
import com.inqwise.context.spi.ContextItemFactory;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(30)
class ForkedClusterCollectionsTransportTest {
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
        String deployment = await(sender.deployVerticle(new VerticleBase() {
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
                var pending = vertx.eventBus().<JsonObject>request(address,
                    new JsonObject().put("token", token).put("state", state).put("remove", removal),
                    new DeliveryOptions().setSendTimeout(5_000));
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
                    assertEquals(child.pid(), reply.body().getLong("pid"));
                    assertEquals(token, reply.body().getString("token"));
                    assertSame(removal ? null : current, FlowIdContext.get());
                    assertEquals(removal, DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                    assertSame(privateItem, PRIVATE.slot().require());
                    assertNull(reply.headers().get(PRIVATE.slot().key()));
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
        }, new DeploymentOptions().setThreadingModel(model)));
        await(sender.undeploy(deployment));
    }

    private static Stream<Arguments> scenarios() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of("value", "empty", "removed", "absent")
                .flatMap(state -> Stream.of(false, true).map(removal -> Arguments.of(model, state, removal))));
    }
}
