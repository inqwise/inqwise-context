package com.inqwise.context;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.extension.StringListContext;
import com.inqwise.context.extension.StringMapContext;
import com.inqwise.context.extension.StringSetContext;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;

import static com.inqwise.context.TransportClasspathTestSupport.check;
import static com.inqwise.context.extension.StringListContext.STEPS;
import static com.inqwise.context.extension.StringMapContext.VALUES;
import static com.inqwise.context.extension.StringSetContext.LABELS;

/** Core-only runtime exercising external collection providers through real discovery. */
public final class ContextCollectionsNode {
    private ContextCollectionsNode() {
    }

    /** Exercises values, empty collections, and removals in all deployed threading models. */
    public static void main(String[] args) throws Exception {
        TransportClasspathTestSupport.requireAbsent("io.vertx.ext.web.client.WebClient",
            "io.vertx.kafka.client.producer.KafkaProducer", "org.junit.jupiter.api.Assertions");
        for (ThreadingModel model : List.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER,
                ThreadingModel.VIRTUAL_THREAD)) {
            runModel(model);
        }
        check(StringMapContext.Factory.constructed.get() == 1, "Factory discovery was not cached");
        check(StringMapContext.Factory.created.get() == 9, "Imports or removals invoked map factories");
        check(StringSetContext.Factory.constructed.get() == 1, "Set factory discovery was not cached");
        check(StringSetContext.Factory.created.get() == 9, "Imports or removals invoked set factories");
        check(StringListContext.Factory.constructed.get() == 1, "List factory discovery was not cached");
        check(StringListContext.Factory.created.get() == 9, "Imports or removals invoked list factories");
        System.out.println("CONTEXT_COLLECTIONS_OK");
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
                                Set<String> expectedLabels = action.equals("empty") ? Set.of() : Set.of("configured", "outgoing");
                                check(LABELS.snapshot().equals(expectedLabels), "EventBus set import");
                                List<String> expectedSteps = action.equals("empty") ? List.of()
                                    : List.of("configured", "outgoing", "outgoing");
                                check(STEPS.snapshot().equals(expectedSteps), "EventBus list order/duplicates import");
                                VALUES.put("downstream", "reply");
                                VALUES.remove("outgoing");
                                LABELS.add("downstream");
                                LABELS.remove("outgoing");
                                if (!action.equals("empty")) {
                                    STEPS.removeAt(1);
                                }
                                STEPS.append("downstream");
                            }
                            ContextMapItem<String, String> current = VALUES.slot().get();
                            ContextSetItem<String> currentSet = LABELS.slot().get();
                            ContextListItem<String> currentList = STEPS.slot().get();
                            FlowIdContext.put(FlowId.builder().withId("remote").build());
                            vertx.timer(1).map(tick -> {
                                check(VALUES.slot().get() == current, "Receiver timer identity");
                                check(LABELS.slot().get() == currentSet, "Receiver timer set identity");
                                check(STEPS.slot().get() == currentList, "Receiver timer list identity");
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
                        check(LABELS.getOrCreate().equals(Set.of("configured")), "Set factory SPI override");
                        check(STEPS.getOrCreate().equals(List.of("configured")), "List factory SPI override");
                        VALUES.put("outgoing", "value");
                        LABELS.add("outgoing");
                        STEPS.appendAll(List.of("outgoing", "outgoing"));
                        if (action.equals("empty")) {
                            VALUES.clear();
                            LABELS.clear();
                            STEPS.clear();
                        } else if (action.equals("removed")) {
                            VALUES.remove();
                            LABELS.remove();
                            STEPS.remove();
                        }
                        ContextMapItem<String, String> sent = VALUES.slot().get();
                        ContextSetItem<String> sentSet = LABELS.slot().get();
                        ContextListItem<String> sentList = STEPS.slot().get();
                        return vertx.eventBus().<String>request("map", action).compose(reply -> {
                            check(reply.body().equals("processed"), "EventBus payload");
                            check(FlowIdContext.require() == identity, "Map import changed canonical FlowId");
                            if (action.equals("removed")) {
                                assertRemoved();
                                VALUES.replace(Map.of("restored", "value"));
                                LABELS.replace(Set.of("restored"));
                                STEPS.replace(List.of("restored", "restored"));
                            } else {
                                Map<String, String> expected = action.equals("empty") ? Map.of("downstream", "reply")
                                    : Map.of("factory", "configured", "downstream", "reply");
                                check(VALUES.snapshot().equals(expected), "Returned map replacement");
                                check(VALUES.slot().get() != sent, "Returned snapshot was not decoded");
                                Set<String> expectedLabels = action.equals("empty") ? Set.of("downstream")
                                    : Set.of("configured", "downstream");
                                check(LABELS.snapshot().equals(expectedLabels), "Returned set replacement");
                                check(LABELS.slot().get() != sentSet, "Returned set was not decoded");
                                List<String> expectedSteps = action.equals("empty") ? List.of("downstream")
                                    : List.of("configured", "outgoing", "downstream");
                                check(STEPS.snapshot().equals(expectedSteps), "Returned list order and duplicate removal");
                                check(STEPS.slot().get() != sentList, "Returned list was not decoded");
                            }
                            Map<String, String> returned = VALUES.snapshot();
                            Set<String> returnedSet = LABELS.snapshot();
                            List<String> returnedList = STEPS.snapshot();
                            return vertx.timer(1).map(tick -> {
                                check(VALUES.snapshot() == returned, "Caller timer snapshot identity");
                                check(LABELS.snapshot() == returnedSet, "Caller timer set identity");
                                check(STEPS.snapshot() == returnedList, "Caller timer list identity");
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
        ContextTransportConfiguration configuration = ContextTransportConfiguration.builder()
            .include(VALUES.slot()).include(LABELS.slot()).include(STEPS.slot()).build();
        HttpContextCarrier carrier = new HttpContextCarrier(MultiMap.caseInsensitiveMultiMap());
        VALUES.replace(Map.of("snapshot", "before"));
        LABELS.replace(Set.of("before"));
        STEPS.replace(List.of("before", "before"));
        ContextTransport.writeTo(carrier, configuration);
        List<String> encoded = carrier.getAll(VALUES.slot().key());
        List<String> encodedSet = carrier.getAll(LABELS.slot().key());
        List<String> encodedList = carrier.getAll(STEPS.slot().key());
        VALUES.put("snapshot", "after");
        LABELS.add("after");
        STEPS.append("after");
        check(carrier.getAll(VALUES.slot().key()).equals(encoded), "Already-exported metadata changed");
        check(carrier.getAll(LABELS.slot().key()).equals(encodedSet), "Already-exported set changed");
        check(carrier.getAll(STEPS.slot().key()).equals(encodedList), "Already-exported list changed");
        ContextTransport.readFrom(carrier, configuration);
        check(VALUES.snapshot().equals(Map.of("snapshot", "before")), "Carrier import did not replace complete map");
        check(LABELS.snapshot().equals(Set.of("before")), "Carrier import did not replace complete set");
        check(STEPS.snapshot().equals(List.of("before", "before")), "Carrier list order/duplicates import");
        VALUES.clear();
        LABELS.clear();
        STEPS.clear();
        ContextTransport.writeTo(carrier, configuration);
        VALUES.remove();
        LABELS.remove();
        STEPS.remove();
        ContextTransport.readFrom(carrier, configuration);
        check(VALUES.snapshot().isEmpty(), "Empty snapshot was confused with removal");
        check(LABELS.snapshot().isEmpty(), "Empty set was confused with removal");
        check(STEPS.snapshot().isEmpty(), "Empty list was confused with removal");
        VALUES.remove();
        LABELS.remove();
        STEPS.remove();
        ContextTransport.writeTo(carrier, configuration);
        VALUES.replace(Map.of("temporary", "value"));
        LABELS.replace(Set.of("temporary"));
        STEPS.replace(List.of("temporary"));
        ContextTransport.readFrom(carrier, configuration);
        assertRemoved();
    }

    private static void assertRemoved() {
        check(VALUES.slot().get() == null, "Whole map removal missing");
        check(LABELS.slot().get() == null, "Whole set removal missing");
        check(STEPS.slot().get() == null, "Whole list removal missing");
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
        try {
            LABELS.getOrCreate();
            throw new AssertionError("Factory revived removed set");
        } catch (IllegalStateException expected) {
            // Creation cannot revive the set marker either.
        }
        try {
            LABELS.add("revival");
            throw new AssertionError("Membership mutation revived removed set");
        } catch (IllegalStateException expected) {
            // Membership changes require an existing item.
        }
        try {
            STEPS.getOrCreate();
            throw new AssertionError("Factory revived removed list");
        } catch (IllegalStateException expected) {
            // List initialization follows the same marker rule.
        }
        try {
            STEPS.append("revival");
            throw new AssertionError("Append revived removed list");
        } catch (IllegalStateException expected) {
            // Appending requires a present list.
        }
    }
}
