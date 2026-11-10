package com.inqwise.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.data.FlowIdService.CodecProfile;
import com.inqwise.context.proxy.ClusterFlowService;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.ReplyException;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.RepeatedTest;

import static com.inqwise.context.ClusterTestSupport.await;
import static com.inqwise.context.ClusterTestSupport.clusterConfig;
import static com.inqwise.context.ClusterTestSupport.startNode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(30)
class ForkedClusterFlowIdTransportTest {
    @TempDir
    static Path temporary;

    private record Child(Process process, Path log) {
    }

    private final List<Child> children = new ArrayList<>();
    private Vertx sender;
    private HazelcastClusterManager manager;
    private String bAddress;
    private String cAddress;

    @BeforeAll
    @Timeout(90)
    void startCluster() throws Exception {
        String cluster = "forked-flow-" + UUID.randomUUID();
        bAddress = "B-" + UUID.randomUUID();
        cAddress = "C-" + UUID.randomUUID();
        manager = new HazelcastClusterManager(clusterConfig(cluster, null));
        sender = await(startNode(manager));
        sender.eventBus().addOutboundInterceptor(new EventBusTransportHandler(
            () -> List.of(new FlowIdService(CodecProfile.LEGACY)))::handleOutbound);
        String seed = "127.0.0.1:" + manager.getHazelcastInstance().getCluster().getLocalMember().getAddress().getPort();
        startChild(cluster, seed, cAddress, "-", "C", "WORKER", "gzip");
        startChild(cluster, seed, bAddress, cAddress, "B", "EVENT_LOOP", "json");
        assertEquals(3, manager.getHazelcastInstance().getCluster().getMembers().size());
        assertNotEquals(children.get(0).process().pid(), children.get(1).process().pid());
    }

    private void startChild(String cluster, String seed, String address, String next,
            String name, String model, String codec) throws Exception {
        Path ready = temporary.resolve(name + ".ready");
        Path log = temporary.resolve(name + ".log");
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-D" + FlowIdService.CODEC_PROPERTY + "=" + codec,
            "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            ForkedClusterNode.class.getName(), cluster, seed, address, next, name, model, ready.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        children.add(new Child(process, log));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(ready)) {
            if (!process.isAlive() || System.nanoTime() >= deadline) {
                throw new AssertionError("Child " + name + " did not become ready:\n" + Files.readString(log));
            }
            Thread.sleep(25);
        }
        assertEquals(Long.toString(process.pid()), Files.readString(ready));
        assertNotEquals(ProcessHandle.current().pid(), process.pid());
    }

    @AfterAll
    @Timeout(90)
    void stopCluster() throws Exception {
        Exception failure = null;
        for (Child child : children.reversed()) {
            try {
                stopChild(child);
            } catch (Exception error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
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
        if (failure != null) {
            throw failure;
        }
    }

    private static void stopChild(Child child) throws Exception {
        Process process = child.process();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Child did not shut down:\n" + Files.readString(child.log()));
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Child exited with " + process.exitValue() + ":\n"
                    + Files.readString(child.log()));
            }
        } finally {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Cannot terminate test child " + process.pid());
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"keep", "replace", "remove", "removed", "readd", "fail"})
    void serviceProxy_threeJvms_preserveValueAndRemovalPolicies(String action) throws Exception {
        FlowId original = FlowId.builder().withId("origin:\u0394\nquoted-\"id\"")
            .withTimestamp(Instant.parse("2026-11-08T10:00:00.123Z")).build();
        String deployment = await(deployScenario(action, original, new JsonObject().put("action", action), ignored -> { }));
        await(sender.undeploy(deployment));
    }

    @RepeatedTest(3)
    void serviceProxy_overlappingChanges_isolateEachRequestAcrossThreeJvms() throws Exception {
        String batch = UUID.randomUUID().toString();
        List<String> actions = List.of("keep", "replace", "remove", "removed", "readd", "fail");
        int size = actions.size() * 2;
        List<Future<String>> pending = new ArrayList<>();
        List<String> deployed = new CopyOnWriteArrayList<>();
        AtomicInteger verified = new AtomicInteger();
        try {
            for (int i = 0; i < size; i++) {
                String action = actions.get(i % actions.size());
                String token = batch + "-" + i;
                FlowId original = FlowId.builder().withId(token)
                    .withTimestamp(Instant.parse("2026-11-08T10:00:00Z").plusMillis(i)).build();
                JsonObject command = new JsonObject().put("action", action).put("batch", batch)
                    .put("size", size).put("token", token).put("delay", 1 + (size - i) * 2);
                pending.add(deployScenario(action, original, command, report -> {
                    assertEquals(token, report.getString("token"));
                    assertEquals(token, report.getJsonObject("child").getString("token"));
                    assertEquals(size, report.getInteger("peersSeen"));
                    assertEquals(size, report.getJsonObject("child").getInteger("peersSeen"));
                    verified.incrementAndGet();
                }).andThen(result -> {
                    if (result.succeeded()) {
                        deployed.add(result.result());
                    }
                }));
            }
            await(Future.join(pending));
            assertEquals(size, verified.get());
        } finally {
            await(Future.join(deployed.stream().map(sender::undeploy).toList()));
        }
    }

    private Future<String> deployScenario(String action, FlowId original, JsonObject command,
            Consumer<JsonObject> verifyAdditional) {
        boolean removed = action.equals("removed") || action.equals("readd");
        return sender.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                assertNull(Storage.get("flow_id"));
                Storage.put("flow_id", original);
                if (removed) {
                    assertSame(original, Storage.remove("flow_id"));
                }
                ClusterFlowService service = new ServiceProxyBuilder(vertx).setAddress(bAddress)
                    .setOptions(new DeliveryOptions().setSendTimeout(5_000)).build(ClusterFlowService.class);
                return service.process(command.encode()).transform(result -> {
                    JsonObject b;
                    assertEquals(action.equals("fail"), result.failed());
                    if (result.failed()) {
                        ReplyException error = assertInstanceOf(ReplyException.class, result.cause());
                        assertEquals(73, error.failureCode());
                        b = new JsonObject(error.getMessage());
                    } else {
                        b = new JsonObject(result.result());
                    }
                    if (removed) {
                        assertNull(Storage.get("flow_id"));
                    } else {
                        assertSame(original, Storage.get("flow_id"));
                    }
                    verifyReport(action, original, removed, b);
                    verifyAdditional.accept(b);
                    return vertx.timer(1).map(ignored -> {
                        if (removed) {
                            assertNull(Storage.get("flow_id"));
                        } else {
                            assertSame(original, Storage.get("flow_id"));
                        }
                        return null;
                    });
                });
            }
        });
    }

    private void verifyReport(String action, FlowId original, boolean removed, JsonObject b) {
        JsonObject c = b.getJsonObject("child");
        assertEquals(children.get(1).process().pid(), b.getLong("pid"));
        assertEquals(children.get(0).process().pid(), c.getLong("pid"));
        assertEquals("B", b.getString("node"));
        assertEquals("C", c.getString("node"));
        FlowIdService legacy = new FlowIdService(CodecProfile.LEGACY);
        assertEquals(removed ? legacy.marshalRemovalToString() : legacy.marshalToString(original), b.getString("header"));
        assertEquals(removed ? null : original.toJson(), b.getJsonObject("flow"));

        JsonObject bAfter = b.getJsonObject("after");
        JsonObject cAfter = c.getJsonObject("after");
        assertEquals(bAfter.getJsonObject("flow"), b.getJsonObject("afterTimer").getJsonObject("flow"));
        assertEquals(cAfter.getJsonObject("flow"), c.getJsonObject("afterTimer").getJsonObject("flow"));
        assertEquals(cAfter.getJsonObject("flow"), c.getJsonObject("returned").getJsonObject("flow"));
        assertEquals(bAfter.getJsonObject("flow"), b.getJsonObject("returned").getJsonObject("flow"));
        assertEquals(bAfter.getJsonObject("flow"), c.getJsonObject("flow"));
        FlowIdService json = new FlowIdService(CodecProfile.JSON);
        assertEquals(bAfter.getJsonObject("flow") == null ? json.marshalRemovalToString()
            : json.marshalToString(new FlowId(bAfter.getJsonObject("flow"))), c.getString("header"));
        String replyHeader = b.getJsonObject("returned").getString("replyHeader");
        FlowIdService gzip = new FlowIdService(CodecProfile.GZIP);
        if (cAfter.getJsonObject("flow") == null) {
            assertTrue(gzip.isRemoval(replyHeader));
        } else {
            assertTrue(replyHeader.startsWith("flow-id:1:gzip:"));
            assertEquals(cAfter.getJsonObject("flow"), ((FlowId) gzip.unmarshalFormString(replyHeader)).toJson());
        }
        if (action.equals("remove") || action.equals("removed")) {
            assertNull(bAfter.getJsonObject("flow"));
            assertNull(cAfter.getJsonObject("flow"));
        } else if (action.equals("keep")) {
            assertEquals(original.toJson(), bAfter.getJsonObject("flow"));
            assertEquals(original.toJson(), cAfter.getJsonObject("flow"));
        } else {
            assertEquals("flow-B", bAfter.getJsonObject("flow").getString("id"));
            assertEquals("flow-C", cAfter.getJsonObject("flow").getString("id"));
            if (!removed) {
                assertEquals(original.toJson().getValue("timestamp"), bAfter.getJsonObject("flow").getValue("timestamp"));
            }
            assertEquals(bAfter.getJsonObject("flow").getValue("timestamp"),
                cAfter.getJsonObject("flow").getValue("timestamp"));
        }
        for (Child child : children) {
            assertTrue(child.process().isAlive());
        }
    }
}
