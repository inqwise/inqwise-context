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
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceBinder;
import io.vertx.serviceproxy.ServiceException;
import io.vertx.serviceproxy.ServiceProxyBuilder;
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
                        process(vertx, message.body(), args.length > 4 ? args[4] : "-").onComplete(result -> {
                            if (result.succeeded()) {
                                message.reply(result.result());
                            } else {
                                message.fail(500, result.cause().toString());
                            }
                        }));
                    var proxy = new ServiceBinder(vertx).setAddress(args[2] + "-proxy").register(ClusterFlowService.class,
                        command -> process(vertx, new JsonObject(command), args.length > 4 ? args[4] : "-").map(JsonObject::encode));
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

    private static Future<JsonObject> process(Vertx vertx, JsonObject command, String nextAddress) {
        try {
            if (command.getBoolean("chain", false)) {
                return processChain(vertx, command, nextAddress);
            }
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

    private static Future<JsonObject> processChain(Vertx vertx, JsonObject command, String nextAddress) {
        boolean terminal = nextAddress.equals("-");
        String token = command.getString("token");
        String state = command.getString("state");
        boolean removal = command.getBoolean("remove");
        ContextInternal scope = ContextInternal.current();
        FlowId incoming = FlowIdContext.require();
        String expected = terminal ? "middle-" + token : token;
        check(incoming.getId().equals(expected), "Chain incoming FlowId");
        assertState(state, expected);
        check(PRIVATE.slot().get() == null, "Private metadata crossed a chain hop");
        PRIVATE.replace(Map.of("node", expected));
        var privateItem = PRIVATE.slot().require();
        var receivedMap = VALUES.slot().get();
        var receivedSet = LABELS.slot().get();
        var receivedList = STEPS.slot().get();
        return vertx.timer(1).compose(tick -> {
            check(ContextInternal.current() == scope && scope.threadingModel() == ThreadingModel.WORKER, "Chain worker context");
            check(FlowIdContext.get() == incoming && VALUES.slot().get() == receivedMap && LABELS.slot().get() == receivedSet
                && STEPS.slot().get() == receivedList, "Chain incoming timer identity");
            if (terminal) {
                if (removal) {
                    remove();
                } else {
                    replace("tail-" + token);
                    VALUES.put("edited", "entry");
                    LABELS.add("edited");
                    STEPS.removeAt(0);
                    STEPS.appendAll(List.of("edited", "edited"));
                }
                FlowIdContext.put(FlowId.builder().withId("tail-" + token).build());
                JsonObject report = new JsonObject().put("pid", ProcessHandle.current().pid()).put("token", token);
                return command.getBoolean("fail", false) ? Future.failedFuture(new ServiceException(73, report.encode()))
                    : Future.succeededFuture(report);
            }
            String forwardedState = state.equals("value") && removal ? "removed"
                : (state.equals("removed") || state.equals("absent")) && !removal ? "value" : state;
            prepare(forwardedState, "middle-" + token);
            FlowId middle = FlowId.builder().withId("middle-" + token).build();
            FlowIdContext.put(middle);
            var sentMap = VALUES.slot().get();
            var sentSet = LABELS.slot().get();
            var sentList = STEPS.slot().get();
            ClusterFlowService next = new ServiceProxyBuilder(vertx).setAddress(nextAddress + "-proxy")
                .setOptions(new DeliveryOptions().setSendTimeout(5_000)).build(ClusterFlowService.class);
            return next.process(command.copy().put("state", forwardedState).encode()).transform(result -> {
                check(ContextInternal.current() == scope && FlowIdContext.get() == middle, "Tail reply changed middle FlowId/context");
                check(PRIVATE.slot().require() == privateItem, "Tail reply changed middle private storage");
                if (removal) {
                    assertState("removed", token);
                } else {
                    check(VALUES.snapshot().equals(Map.of("value", "tail-" + token, "edited", "entry")), "Tail map return");
                    check(LABELS.snapshot().equals(Set.of("tail-" + token, "edited")), "Tail set return");
                    check(STEPS.snapshot().equals(List.of("tail-" + token, "edited", "edited")), "Tail list return");
                    check(VALUES.slot().get() != sentMap && LABELS.slot().get() != sentSet && STEPS.slot().get() != sentList,
                        "Tail collections were not decoded into middle scope");
                }
                JsonObject child;
                if (command.getBoolean("fail", false)) {
                    check(result.failed() && result.cause() instanceof ReplyException, "Expected tail service failure");
                    ReplyException failure = (ReplyException) result.cause();
                    check(failure.failureCode() == 73, "Tail service failure code");
                    child = new JsonObject(failure.getMessage());
                } else {
                    if (result.failed()) {
                        return Future.failedFuture(result.cause());
                    }
                    child = new JsonObject(result.result());
                }
                check(child.getLong("pid") != ProcessHandle.current().pid() && token.equals(child.getString("token")),
                    "Tail process/report isolation");
                var returnedMap = VALUES.slot().get();
                var returnedSet = LABELS.slot().get();
                var returnedList = STEPS.slot().get();
                return vertx.timer(1).compose(ignored -> {
                    check(ContextInternal.current() == scope && FlowIdContext.get() == middle, "Middle return timer identity");
                    check(VALUES.slot().get() == returnedMap && LABELS.slot().get() == returnedSet
                        && STEPS.slot().get() == returnedList, "Middle collection return timer identity");
                    JsonObject report = new JsonObject().put("pid", ProcessHandle.current().pid()).put("token", token)
                        .put("peersSeen", 1).put("child", child).put("forwardedState", forwardedState);
                    return command.getBoolean("fail", false) ? Future.failedFuture(new ServiceException(73, report.encode()))
                        : Future.succeededFuture(report);
                });
            });
        });
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
