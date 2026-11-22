package com.inqwise.context;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.extension.StringMapContext;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;

import static com.inqwise.context.TransportClasspathTestSupport.check;
import static com.inqwise.context.extension.StringMapContext.VALUES;

/** Core-only runtime exercising external map providers through real discovery. */
public final class ContextMapNode {
    private ContextMapNode() {
    }

    /** Exercises values, empty maps, and removals in all deployed threading models. */
    public static void main(String[] args) throws Exception {
        TransportClasspathTestSupport.requireAbsent("io.vertx.ext.web.client.WebClient",
            "io.vertx.kafka.client.producer.KafkaProducer", "org.junit.jupiter.api.Assertions");
        for (ThreadingModel model : List.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER,
                ThreadingModel.VIRTUAL_THREAD)) {
            runModel(model);
        }
        check(StringMapContext.Factory.constructed.get() == 1, "Factory discovery was not cached");
        check(StringMapContext.Factory.created.get() == 9, "Imports or removals invoked map factories");
        System.out.println("CONTEXT_MAP_OK");
    }

    private static void runModel(ThreadingModel model) throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            vertx.deployVerticle(new VerticleBase() {
                @Override
                public Future<?> start() {
                    return vertx.eventBus().<String>consumer("map", message -> {
                        try {
                            check(FlowIdContext.require().getId().equals("caller"), "EventBus FlowId import");
                            String action = message.body();
                            if (action.equals("removed")) {
                                assertRemoved();
                            } else {
                                Map<String, String> expected = action.equals("empty") ? Map.of()
                                    : Map.of("factory", "configured", "outgoing", "value");
                                check(VALUES.snapshot().equals(expected), "EventBus snapshot import");
                                VALUES.put("downstream", "reply");
                                VALUES.remove("outgoing");
                            }
                            ContextMapItem<String, String> current = VALUES.slot().get();
                            FlowIdContext.put(FlowId.builder().withId("remote").build());
                            vertx.timer(1).map(tick -> {
                                check(VALUES.slot().get() == current, "Receiver timer identity");
                                return tick;
                            }).onComplete(result -> {
                                if (result.succeeded()) {
                                    message.reply("processed");
                                } else {
                                    message.fail(500, result.cause().toString());
                                }
                            });
                        } catch (Throwable failure) {
                            message.fail(500, failure.toString());
                        }
                    }).completion();
                }
            }, new DeploymentOptions().setThreadingModel(model))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            for (String action : List.of("value", "empty", "removed")) {
                vertx.deployVerticle(new VerticleBase() {
                    @Override
                    public Future<?> start() {
                        FlowId identity = FlowId.builder().withId("caller").build();
                        FlowIdContext.put(identity);
                        check(VALUES.getOrCreate().equals(Map.of("factory", "configured")), "Factory SPI override");
                        VALUES.put("outgoing", "value");
                        if (action.equals("empty")) {
                            VALUES.clear();
                        } else if (action.equals("removed")) {
                            VALUES.remove();
                        }
                        ContextMapItem<String, String> sent = VALUES.slot().get();
                        return vertx.eventBus().<String>request("map", action).compose(reply -> {
                            check(reply.body().equals("processed"), "EventBus payload");
                            check(FlowIdContext.require() == identity, "Map import changed canonical FlowId");
                            if (action.equals("removed")) {
                                assertRemoved();
                                VALUES.replace(Map.of("restored", "value"));
                            } else {
                                Map<String, String> expected = action.equals("empty") ? Map.of("downstream", "reply")
                                    : Map.of("factory", "configured", "downstream", "reply");
                                check(VALUES.snapshot().equals(expected), "Returned map replacement");
                                check(VALUES.slot().get() != sent, "Returned snapshot was not decoded");
                            }
                            Map<String, String> returned = VALUES.snapshot();
                            return vertx.timer(1).map(tick -> {
                                check(VALUES.snapshot() == returned, "Caller timer snapshot identity");
                                check(FlowIdContext.require() == identity, "Caller timer FlowId identity");
                                verifyCarrierSnapshot();
                                return tick;
                            });
                        });
                    }
                }, new DeploymentOptions().setThreadingModel(model))
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static void verifyCarrierSnapshot() {
        ContextTransportConfiguration configuration = ContextTransportConfiguration.builder().include(VALUES.slot()).build();
        HttpContextCarrier carrier = new HttpContextCarrier(MultiMap.caseInsensitiveMultiMap());
        VALUES.replace(Map.of("snapshot", "before"));
        ContextTransport.writeTo(carrier, configuration);
        List<String> encoded = carrier.getAll(VALUES.slot().key());
        VALUES.put("snapshot", "after");
        check(carrier.getAll(VALUES.slot().key()).equals(encoded), "Already-exported metadata changed");
        ContextTransport.readFrom(carrier, configuration);
        check(VALUES.snapshot().equals(Map.of("snapshot", "before")), "Carrier import did not replace complete map");
        VALUES.clear();
        ContextTransport.writeTo(carrier, configuration);
        VALUES.remove();
        ContextTransport.readFrom(carrier, configuration);
        check(VALUES.snapshot().isEmpty(), "Empty snapshot was confused with removal");
        VALUES.remove();
        ContextTransport.writeTo(carrier, configuration);
        VALUES.replace(Map.of("temporary", "value"));
        ContextTransport.readFrom(carrier, configuration);
        assertRemoved();
    }

    private static void assertRemoved() {
        check(VALUES.slot().get() == null, "Whole map removal missing");
        try {
            VALUES.getOrCreate();
            throw new AssertionError("Factory revived removed map");
        } catch (IllegalStateException expected) {
            // Creation cannot replace a transportable removal marker.
        }
        try {
            VALUES.put("revival", "forbidden");
            throw new AssertionError("Entry mutation revived removed map");
        } catch (IllegalStateException expected) {
            // Only explicit whole-map replacement reintroduces it.
        }
    }
}
