package com.inqwise.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.proxy.ClusterFlowService;
import com.inqwise.context.spi.ContextChainDataItem;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceBinder;
import io.vertx.serviceproxy.ServiceException;
import io.vertx.serviceproxy.ServiceProxyBuilder;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;

import static com.inqwise.context.ClusterTestSupport.await;
import static com.inqwise.context.ClusterTestSupport.clusterConfig;
import static com.inqwise.context.ClusterTestSupport.startNode;

/** Standalone test node; stdin closure also shuts it down if its parent exits. */
public final class ForkedClusterNode {
    private static final ConcurrentMap<String, Batch> batches = new ConcurrentHashMap<>();

    private static final class Batch {
        final int size;
        final AtomicInteger arrived = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();

        Batch(int size) {
            this.size = size;
        }
    }

    private record WireHeader(String value) implements ContextChainDataItem {
    }

    private ForkedClusterNode() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        HazelcastClusterManager manager = new HazelcastClusterManager(clusterConfig(args[0], args[1]));
        Vertx node = null;
        try {
            node = await(startNode(manager));
            String address = args[2];
            String nextAddress = args[3];
            String name = args[4];
            node.eventBus().addInboundInterceptor(delivery -> {
                try {
                    if (delivery.message().address().equals(address)) {
                        Storage.put("observed-header", new WireHeader(delivery.message().headers().get("flow_id")));
                    } else if (delivery.message().headers().contains("flow_id")) {
                        Storage.put("observed-reply-header", new WireHeader(delivery.message().headers().get("flow_id")));
                    }
                } finally {
                    delivery.next();
                }
            });
            await(node.deployVerticle(new VerticleBase() {
                @Override
                public Future<?> start() {
                    ClusterFlowService next = nextAddress.equals("-") ? null : new ServiceProxyBuilder(vertx)
                        .setAddress(nextAddress).setOptions(new DeliveryOptions().setSendTimeout(5_000))
                        .build(ClusterFlowService.class);
                    return new ServiceBinder(vertx).setAddress(address).register(ClusterFlowService.class,
                        action -> process(action, name, next)).completion();
                }
            }, new DeploymentOptions().setThreadingModel(ThreadingModel.valueOf(args[5]))));
            Path ready = Path.of(args[6]);
            Path pending = ready.resolveSibling(ready.getFileName() + ".pending");
            Files.writeString(pending, Long.toString(ProcessHandle.current().pid()));
            Files.move(pending, ready, StandardCopyOption.ATOMIC_MOVE);
            System.in.read();
        } finally {
            try {
                if (node != null) {
                    await(node.close());
                }
            } finally {
                if (manager.getHazelcastInstance() != null
                        && manager.getHazelcastInstance().getLifecycleService().isRunning()) {
                    manager.getHazelcastInstance().shutdown();
                }
            }
        }
    }

    private static Future<String> process(String command, String name, ClusterFlowService next) {
        JsonObject options = command.startsWith("{") ? new JsonObject(command) : new JsonObject().put("action", command);
        String action = options.getString("action");
        String batchId = options.getString("batch");
        Batch batch = batchId == null ? null : batches.computeIfAbsent(batchId,
            ignored -> new Batch(options.getInteger("size")));
        if (batch != null) {
            batch.arrived.incrementAndGet();
        }
        JsonObject report = snapshot().put("pid", ProcessHandle.current().pid()).put("node", name)
            .put("token", options.getString("token"));
        FlowId incoming = (FlowId) Storage.get("flow_id");
        switch (action) {
            case "replace", "readd", "fail" -> Storage.put("flow_id", incoming == null
                ? FlowId.builder().withId("flow-" + name).build()
                : FlowId.builderFrom(incoming).withId("flow-" + name).build());
            case "remove" -> Storage.remove("flow_id");
            default -> { }
        }
        ContextChainDataItem local = Storage.get("flow_id");
        report.put("after", snapshot());
        Vertx owner = Vertx.currentContext().owner();
        Future<Void> ready = batch == null ? Future.succeededFuture()
            : waitForPeers(owner, batch, System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
        return ready.compose(ignored -> owner.timer(options.getInteger("delay", 1))).compose(ignored -> {
            if (Storage.get("flow_id") != local) {
                return Future.<String>failedFuture(new ServiceException(500, "Timer changed local instance in " + name));
            }
            report.put("peersSeen", batch == null ? 1 : batch.arrived.get());
            report.put("afterTimer", snapshot());
            return next == null ? Future.succeededFuture("") : next.process(command);
        }).transform(result -> {
            if (Storage.get("flow_id") != local) {
                return Future.failedFuture(new ServiceException(500, "Caller instance changed in " + name));
            }
            report.put("returned", snapshot());
            if (next == null && result.succeeded()) {
                return action.equals("fail") ? Future.failedFuture(new ServiceException(73, report.encode()))
                    : Future.succeededFuture(report.encode());
            }
            if (result.failed()) {
                if (!(result.cause() instanceof ReplyException failure) || failure.failureCode() != 73) {
                    return Future.failedFuture(result.cause());
                }
                report.put("child", new JsonObject(failure.getMessage()));
                return Future.failedFuture(new ServiceException(73, report.encode()));
            }
            report.put("child", new JsonObject(result.result()));
            return Future.succeededFuture(report.encode());
        }).andThen(ignored -> {
            if (batch != null && batch.completed.incrementAndGet() == batch.size) {
                batches.remove(batchId, batch);
            }
        });
    }

    private static Future<Void> waitForPeers(Vertx owner, Batch batch, long deadline) {
        if (batch.arrived.get() == batch.size) {
            return Future.succeededFuture();
        }
        if (System.nanoTime() >= deadline) {
            return Future.failedFuture("Concurrent request batch did not arrive");
        }
        // Each request waits through its own timer to retain its calling context.
        return owner.timer(2).compose(ignored -> waitForPeers(owner, batch, deadline));
    }

    private static JsonObject snapshot() {
        FlowId value = (FlowId) Storage.get("flow_id");
        WireHeader header = (WireHeader) Storage.get("observed-header");
        WireHeader replyHeader = (WireHeader) Storage.get("observed-reply-header");
        return new JsonObject().put("flow", value == null ? null : value.toJson())
            .put("header", header == null ? null : header.value())
            .put("replyHeader", replyHeader == null ? null : replyHeader.value());
    }
}
