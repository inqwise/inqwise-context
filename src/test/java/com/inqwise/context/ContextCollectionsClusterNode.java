package com.inqwise.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.extension.StringListContext;
import com.inqwise.context.extension.StringMapContext;
import com.inqwise.context.extension.StringSetContext;
import com.inqwise.context.proxy.ClusterFlowService;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceBinder;
import io.vertx.serviceproxy.ServiceException;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;

import static com.inqwise.context.ClusterTestSupport.await;
import static com.inqwise.context.ClusterTestSupport.clusterConfig;
import static com.inqwise.context.ClusterTestSupport.startNode;
import static com.inqwise.context.TransportClasspathTestSupport.check;
import static com.inqwise.context.extension.StringListContext.STEPS;
import static com.inqwise.context.extension.StringMapContext.VALUES;
import static com.inqwise.context.extension.StringSetContext.LABELS;

/** Separate clustered JVM discovering external collection codecs through SPI. */
public final class ContextCollectionsClusterNode {
    static final ContextMap<String, String> PRIVATE = ContextMaps.define("private_map", String.class, String.class);
    private static final ConcurrentMap<String, Batch> batches = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Pending> pendingReplies = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, JsonObject> lateHeaders = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, String> lateFailures = new ConcurrentHashMap<>();

    private record Pending(ContextInternal context, Promise<Void> release) {
    }

    private static final class Batch {
        final int size;
        final AtomicInteger arrived = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();

        Batch(int size) {
            this.size = size;
        }
    }

    private ContextCollectionsClusterNode() {
    }

    /** Starts a worker receiver, publishes readiness, and stops when stdin closes. */
    public static void main(String[] args) throws Exception {
        HazelcastClusterManager manager = new HazelcastClusterManager(clusterConfig(args[0], args[1]));
        Vertx node = null;
        try {
            node = await(startNode(manager));
            node.eventBus().addOutboundInterceptor(delivery -> {
                JsonObject captured = null;
                String token = null;
                try {
                    if (delivery.body() instanceof String text && text.startsWith("{")) {
                        JsonObject report = new JsonObject(text);
                        if (report.getBoolean("late", false)) {
                            token = report.getString("token");
                            captured = new JsonObject();
                            for (String key : List.of("flow_id", VALUES.slot().key(), LABELS.slot().key(),
                                    STEPS.slot().key(), PRIVATE.slot().key())) {
                                captured.put(key, delivery.message().headers().get(key));
                            }
                        }
                    }
                } finally {
                    delivery.next();
                }
                if (captured != null) {
                    lateHeaders.put(token, captured);
                }
            });
            await(node.deployVerticle(new VerticleBase() {
                @Override
                public Future<?> start() {
                    var direct = vertx.eventBus().<JsonObject>consumer(args[2], message ->
                        process(vertx, message.body()).onComplete(result -> {
                            if (result.succeeded()) {
                                message.reply(result.result());
                            } else {
                                message.fail(500, result.cause().toString());
                            }
                        }));
                    var proxy = new ServiceBinder(vertx).setAddress(args[2] + "-proxy").register(ClusterFlowService.class,
                        command -> process(vertx, new JsonObject(command)).map(JsonObject::encode));
                    var control = vertx.eventBus().<JsonObject>consumer(args[2] + "-control", message -> {
                        // Coordination replies must not echo caller metadata back into its storage.
                        DefaultStorage.reset(ContextInternal.current());
                        try {
                            String token = message.body().getString("token");
                            JsonObject report = new JsonObject().put("pid", ProcessHandle.current().pid());
                            if (message.body().getString("operation").equals("release")) {
                                Pending pending = pendingReplies.remove(token);
                                check(pending != null, "Unknown held reply");
                                pending.context().runOnContext(ignored -> pending.release().complete());
                                report.put("released", true);
                            } else {
                                report.put("entered", pendingReplies.containsKey(token)).put("headers", lateHeaders.remove(token))
                                    .put("failure", lateFailures.remove(token));
                            }
                            message.reply(report);
                        } catch (Throwable failure) {
                            message.fail(500, failure.toString());
                        }
                    });
                    return Future.all(direct.completion(), proxy.completion(), control.completion());
                }
            }, new DeploymentOptions().setThreadingModel(ThreadingModel.WORKER)));
            Path ready = Path.of(args[3]);
            Path pending = ready.resolveSibling(ready.getFileName() + ".pending");
            Files.writeString(pending, Long.toString(ProcessHandle.current().pid()));
            Files.move(pending, ready, StandardCopyOption.ATOMIC_MOVE);
            System.in.read();
            check(batches.isEmpty(), "Incomplete clustered collection batch");
            check(pendingReplies.isEmpty() && lateHeaders.isEmpty() && lateFailures.isEmpty(), "Incomplete clustered late reply");
            check(StringMapContext.Factory.created.get() == 0 && StringSetContext.Factory.created.get() == 0
                && StringListContext.Factory.created.get() == 0, "Remote import invoked factories");
        } finally {
            try {
                if (node != null) {
                    await(node.close());
                }
            } finally {
                if (manager.getHazelcastInstance() != null && manager.getHazelcastInstance().getLifecycleService().isRunning()) {
                    manager.getHazelcastInstance().shutdown();
                }
            }
        }
    }

    private static Future<JsonObject> process(Vertx vertx, JsonObject command) {
        try {
            if (command.getBoolean("inspect", false)) {
                return inspect(vertx, command);
            }
            ContextInternal receiver = ContextInternal.current();
            String token = command.getString("token");
            assertState(command.getString("state"), token);
            check(PRIVATE.slot().get() == null, "Private state leaked into remote request");
            FlowId received = FlowIdContext.require();
            check(received.getId().equals(token), "Remote FlowId value");
            var incomingMap = VALUES.slot().get();
            var incomingSet = LABELS.slot().get();
            var incomingList = STEPS.slot().get();
            PRIVATE.replace(Map.of("remote", token));
            var privateItem = PRIVATE.slot().require();
            String batchId = command.getString("batch");
            Batch batch = batchId == null ? null : batches.computeIfAbsent(batchId,
                ignored -> new Batch(command.getInteger("size")));
            if (batch != null) {
                check(batch.size == command.getInteger("size"), "Inconsistent batch size");
                batch.arrived.incrementAndGet();
            }
            Future<Void> ready;
            if (command.getBoolean("hold", false)) {
                Promise<Void> release = receiver.promise();
                check(pendingReplies.putIfAbsent(token, new Pending(receiver, release)) == null, "Duplicate held reply");
                ready = release.future();
            } else {
                ready = batch == null ? Future.succeededFuture()
                    : waitForPeers(vertx, batch, System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
            }
            return ready.compose(ignored -> vertx.timer(1)).map(tick -> {
                check(ContextInternal.current() == receiver && receiver.threadingModel() == ThreadingModel.WORKER,
                    "Remote worker timer context");
                check(FlowIdContext.get() == received, "Remote timer FlowId identity");
                check(VALUES.slot().get() == incomingMap && LABELS.slot().get() == incomingSet
                    && STEPS.slot().get() == incomingList, "Remote timer collection identity");
                assertState(command.getString("state"), token);
                check(PRIVATE.slot().require() == privateItem
                    && PRIVATE.snapshot().equals(Map.of("remote", token)), "Remote private storage crossed requests");
                if (command.getBoolean("remove")) {
                    remove();
                } else {
                    replace("returned-" + token);
                    VALUES.put("edited", "entry");
                    LABELS.add("edited");
                    STEPS.removeAt(0);
                    STEPS.appendAll(List.of("edited", "edited"));
                }
                FlowIdContext.put(FlowId.builder().withId("remote-return").build());
                return new JsonObject().put("pid", ProcessHandle.current().pid()).put("token", token)
                    .put("peersSeen", batch == null ? 1 : batch.arrived.get()).put("late", command.getBoolean("hold", false));
            }).compose(report -> command.getBoolean("fail", false)
                ? Future.<JsonObject>failedFuture(new ServiceException(73, report.encode()))
                : Future.succeededFuture(report)).andThen(ignored -> {
                if (batch != null && batch.completed.incrementAndGet() == batch.size) {
                    batches.remove(batchId, batch);
                }
            }).onFailure(failure -> {
                if (command.getBoolean("hold", false)) {
                    lateFailures.put(token, failure.toString());
                }
            });
        } catch (Throwable failure) {
            return Future.failedFuture(failure);
        }
    }

    private static Future<JsonObject> inspect(Vertx vertx, JsonObject command) {
        ContextInternal receiver = ContextInternal.current();
        assertState(command.getString("state"), command.getString("value"));
        check(PRIVATE.slot().get() == null, "Private metadata reached follow-up service");
        FlowId flow = FlowIdContext.get();
        if (command.getBoolean("flowRemoved")) {
            check(flow == null && DefaultStorage.isRemoved(DefaultStorage.getEntry(receiver, "flow_id")),
                "Follow-up lost caller FlowId removal");
        } else {
            check(flow != null && flow.getId().equals(command.getString("flow")), "Follow-up imported late FlowId");
        }
        var map = VALUES.slot().get();
        var set = LABELS.slot().get();
        var list = STEPS.slot().get();
        return vertx.timer(1).map(tick -> {
            check(ContextInternal.current() == receiver && receiver.threadingModel() == ThreadingModel.WORKER,
                "Follow-up worker timer context");
            check(FlowIdContext.get() == flow && VALUES.slot().get() == map && LABELS.slot().get() == set
                && STEPS.slot().get() == list, "Follow-up timer changed stored instances");
            return new JsonObject().put("pid", ProcessHandle.current().pid()).put("token", command.getString("token"));
        });
    }

    private static Future<Void> waitForPeers(Vertx vertx, Batch batch, long deadline) {
        if (batch.arrived.get() == batch.size) {
            return Future.succeededFuture();
        }
        if (System.nanoTime() >= deadline) {
            return Future.failedFuture("Concurrent collection requests did not all arrive");
        }
        // Poll through each request's timer rather than sharing a future bound to another context.
        return vertx.timer(2).compose(ignored -> waitForPeers(vertx, batch, deadline));
    }

    static void prepare(String state, String token) {
        switch (state) {
            case "value" -> replace(token);
            case "empty" -> {
                VALUES.replace(Map.of());
                LABELS.replace(Set.of());
                STEPS.replace(List.of());
            }
            case "removed" -> remove();
            case "absent" -> { }
            default -> throw new AssertionError("Unknown collection state");
        }
    }

    static void replace(String token) {
        VALUES.replace(Map.of("value", token));
        LABELS.replace(Set.of(token));
        STEPS.replace(List.of(token, token));
    }

    static void remove() {
        VALUES.remove();
        LABELS.remove();
        STEPS.remove();
    }

    static void assertState(String state, String token) {
        switch (state) {
            case "value" -> {
                check(VALUES.snapshot().equals(Map.of("value", token)), "Network map value");
                check(LABELS.snapshot().equals(Set.of(token)), "Network set value");
                check(STEPS.snapshot().equals(List.of(token, token)), "Network list order/duplicates");
            }
            case "empty" -> check(VALUES.snapshot().isEmpty() && LABELS.snapshot().isEmpty() && STEPS.snapshot().isEmpty(),
                "Network typed empty values");
            case "removed", "absent" -> {
                ContextInternal context = ContextInternal.current();
                for (String key : List.of(VALUES.slot().key(), LABELS.slot().key(), STEPS.slot().key())) {
                    check(DefaultStorage.get(context, key) == null, "Unexpected network collection");
                    check(DefaultStorage.isRemoved(DefaultStorage.getEntry(context, key)) == state.equals("removed"),
                        "Network removal/absence distinction");
                }
            }
            default -> throw new AssertionError("Unknown collection state");
        }
    }
}
