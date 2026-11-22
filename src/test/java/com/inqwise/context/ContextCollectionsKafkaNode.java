package com.inqwise.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.extension.StringListContext;
import com.inqwise.context.extension.StringMapContext;
import com.inqwise.context.extension.StringSetContext;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.consumer.impl.KafkaConsumerRecordImpl;
import io.vertx.kafka.client.producer.KafkaHeader;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import static com.inqwise.context.TransportClasspathTestSupport.check;
import static com.inqwise.context.extension.StringListContext.STEPS;
import static com.inqwise.context.extension.StringMapContext.VALUES;
import static com.inqwise.context.extension.StringSetContext.LABELS;

/** Kafka-only collection propagation using native mock records, without a broker. */
public final class ContextCollectionsKafkaNode {
    private static final ContextMap<String, String> PRIVATE = ContextMaps.define("private_map", String.class, String.class);
    private static final ContextTransportConfiguration CONFIGURATION = ContextTransportConfiguration.builder()
        .include(FlowIdContext.slot()).include(VALUES.slot()).include(LABELS.slot()).include(STEPS.slot()).build();

    private ContextCollectionsKafkaNode() {
    }

    /** Exercises one-way collection snapshots across two independently scoped consumers. */
    public static void main(String[] args) throws Exception {
        TransportClasspathTestSupport.requireAbsent("io.vertx.ext.web.client.WebClient", "org.junit.jupiter.api.Assertions");
        Vertx vertx = Vertx.vertx();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        vertx.exceptionHandler(failure -> unexpected.compareAndSet(null, failure));
        MockProducer<String, String> nativeProducer = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, nativeProducer);
        var sender = KafkaContextTransport.sender(producer, CONFIGURATION, "source", "forwarded");
        try {
            for (ThreadingModel model : List.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)) {
                for (String state : List.of("value", "empty", "removed", "absent")) {
                    for (boolean removal : List.of(false, true)) {
                        vertx.deployVerticle(new VerticleBase() {
                            @Override
                            public Future<?> start() {
                                ContextInternal caller = ContextInternal.current();
                                FlowId original = FlowId.builder().withId(model + "-" + state + "-" + removal).build();
                                FlowIdContext.put(original);
                                prepare(state);
                                PRIVATE.replace(Map.of("caller", "private"));
                                var privateItem = PRIVATE.slot().require();
                                KafkaProducerRecord<String, String> input = KafkaProducerRecord.create("source", "key", "payload");
                                input.addHeader("application", "retained");
                                var acknowledgment = sender.send(input);
                                // Mutate after send, before dispatch: the record must retain the exported snapshot.
                                replace("pending");
                                var pendingMap = VALUES.slot().require();
                                var pendingSet = LABELS.slot().require();
                                var pendingList = STEPS.slot().require();
                                return acknowledgment.compose(ack -> {
                                    var source = nativeProducer.history().getLast();
                                    check(ContextInternal.current() == caller && FlowIdContext.get() == original, "Producer acknowledgment state");
                                    check(input.headers().size() == 1, "Sender mutated original record headers");
                                    Promise<Void> processed = Promise.promise();
                                    Handler<KafkaConsumerRecord<String, String>> first = KafkaContextTransport.handler(CONFIGURATION, message -> {
                                        try {
                                            ContextInternal scope = ContextInternal.current();
                                            FlowId incoming = FlowIdContext.require();
                                            check(scope != caller && incoming != original && incoming.getId().equals(original.getId()), "First consumer FlowId/context");
                                            check(message.key().equals("key") && message.value().equals("payload"), "Source key/payload");
                                            check(message.headers().getFirst().value().toString().equals("retained"), "Application header");
                                            check(PRIVATE.slot().get() == null, "Caller private state leaked");
                                            assertState(state);
                                            var sentMap = VALUES.slot().get();
                                            var sentSet = LABELS.slot().get();
                                            var sentList = STEPS.slot().get();
                                            vertx.timer(1).compose(tick -> {
                                                check(ContextInternal.current() == scope && FlowIdContext.get() == incoming, "First consumer timer identity");
                                                check(VALUES.slot().get() == sentMap && LABELS.slot().get() == sentSet
                                                    && STEPS.slot().get() == sentList, "First consumer timer snapshots");
                                                if (removal) {
                                                    VALUES.remove();
                                                    LABELS.remove();
                                                    STEPS.remove();
                                                } else {
                                                    replace("forwarded");
                                                    VALUES.put("edited", "entry");
                                                    LABELS.add("edited");
                                                    STEPS.removeAt(0);
                                                    STEPS.appendAll(List.of("edited", "edited"));
                                                }
                                                PRIVATE.replace(Map.of("consumer", "private"));
                                                return sender.send(KafkaProducerRecord.create("forwarded", "next", "derived"));
                                            }).compose(forwardedAck -> {
                                                check(ContextInternal.current() == scope, "Forward acknowledgment context");
                                                var forwardedMap = VALUES.slot().get();
                                                var forwardedSet = LABELS.slot().get();
                                                var forwardedList = STEPS.slot().get();
                                                Promise<Void> completed = Promise.promise();
                                                Handler<KafkaConsumerRecord<String, String>> second = KafkaContextTransport.handler(CONFIGURATION, later -> {
                                                    try {
                                                        ContextInternal next = ContextInternal.current();
                                                        check(next != scope && next != caller, "Second consumer isolation");
                                                        check(later.key().equals("next") && later.value().equals("derived"), "Forwarded key/payload");
                                                        check(PRIVATE.slot().get() == null, "First consumer private state leaked");
                                                        FlowId decoded = FlowIdContext.require();
                                                        check(decoded != incoming && decoded.getId().equals(incoming.getId()), "Forwarded FlowId decoding");
                                                        if (removal) {
                                                            assertState("removed");
                                                        } else {
                                                            check(VALUES.snapshot().equals(Map.of("value", "forwarded", "edited", "entry")), "Forwarded map edits");
                                                            check(LABELS.snapshot().equals(Set.of("forwarded", "edited")), "Forwarded set edits");
                                                            check(STEPS.snapshot().equals(List.of("forwarded", "edited", "edited")), "Forwarded list order/duplicates");
                                                            check(VALUES.slot().get() != forwardedMap && LABELS.slot().get() != forwardedSet
                                                                && STEPS.slot().get() != forwardedList, "Forwarded containers were not decoded");
                                                        }
                                                        replace("second-local");
                                                        FlowIdContext.remove();
                                                        var local = STEPS.slot().require();
                                                        vertx.timer(1).map(tick -> {
                                                            check(ContextInternal.current() == next && FlowIdContext.get() == null, "Second timer context/FlowId");
                                                            check(STEPS.slot().require() == local, "Second timer list identity");
                                                            return (Void) null;
                                                        }).onComplete(completed);
                                                    } catch (Throwable failure) {
                                                        completed.tryFail(failure);
                                                    }
                                                }, "forwarded");
                                                second.handle(received(nativeProducer.history().getLast()));
                                                check(ContextInternal.current() == scope && FlowIdContext.get() == incoming, "Second dispatch changed first context/FlowId");
                                                check(VALUES.slot().get() == forwardedMap && LABELS.slot().get() == forwardedSet
                                                    && STEPS.slot().get() == forwardedList, "Second consumer changed first snapshots");
                                                return ContextPropagation.bind(completed.future());
                                            }).onComplete(processed);
                                        } catch (Throwable failure) {
                                            processed.tryFail(failure);
                                        }
                                    }, "source");
                                    first.handle(received(source));
                                    check(ContextInternal.current() == caller, "Producer context was not restored");
                                    return ContextPropagation.bind(processed.future());
                                }).compose(done -> vertx.timer(1)).map(tick -> {
                                    check(ContextInternal.current() == caller && caller.threadingModel() == model, "Producer completion model/context");
                                    check(Thread.currentThread().isVirtual() == (model == ThreadingModel.VIRTUAL_THREAD), "Producer thread model");
                                    check(FlowIdContext.get() == original, "Consumer changes returned through acknowledgment");
                                    check(PRIVATE.slot().require() == privateItem, "Producer private state changed");
                                    check(VALUES.slot().require() == pendingMap && LABELS.slot().require() == pendingSet
                                        && STEPS.slot().require() == pendingList, "Consumer changes returned to producer collections");
                                    check(pendingMap.snapshot().equals(Map.of("value", "pending"))
                                        && pendingSet.snapshot().equals(Set.of("pending"))
                                        && pendingList.snapshot().equals(List.of("pending", "pending")), "Pending producer snapshots changed");
                                    return tick;
                                });
                            }
                        }, new DeploymentOptions().setThreadingModel(model)).toCompletionStage().toCompletableFuture().get(6, TimeUnit.SECONDS);
                    }
                }
            }
            check(nativeProducer.history().size() == 48, "Missing source/forwarded records");
            check(StringMapContext.Factory.created.get() == 18 && StringSetContext.Factory.created.get() == 18
                && StringListContext.Factory.created.get() == 18, "Import or absent metadata invoked factories");
            check(unexpected.get() == null, "Unexpected asynchronous failure");
            System.out.println("CONTEXT_COLLECTIONS_KAFKA_OK");
        } finally {
            try {
                producer.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void prepare(String state) {
        if (state.equals("absent")) {
            return;
        }
        VALUES.getOrCreate();
        LABELS.getOrCreate();
        STEPS.getOrCreate();
        if (state.equals("value")) {
            replace("sent");
        } else if (state.equals("empty")) {
            VALUES.clear();
            LABELS.clear();
            STEPS.clear();
        } else {
            VALUES.remove();
            LABELS.remove();
            STEPS.remove();
        }
    }

    private static void replace(String value) {
        VALUES.replace(Map.of("value", value));
        LABELS.replace(Set.of(value));
        STEPS.replace(List.of(value, value));
    }

    private static void assertState(String state) {
        if (state.equals("value")) {
            check(VALUES.snapshot().equals(Map.of("value", "sent")), "Sent map snapshot");
            check(LABELS.snapshot().equals(Set.of("sent")), "Sent set snapshot");
            check(STEPS.snapshot().equals(List.of("sent", "sent")), "Sent list order/duplicates");
        } else if (state.equals("empty")) {
            check(VALUES.snapshot().isEmpty() && LABELS.snapshot().isEmpty() && STEPS.snapshot().isEmpty(), "Typed empty containers");
        } else {
            check(VALUES.slot().get() == null && LABELS.slot().get() == null && STEPS.slot().get() == null, "Unexpected collection values");
            List<KafkaHeader> headers = new ArrayList<>();
            KafkaContextCarrier carrier = new KafkaContextCarrier(() -> headers);
            ContextTransport.writeTo(carrier, CONFIGURATION);
            boolean removed = state.equals("removed");
            for (String key : List.of(VALUES.slot().key(), LABELS.slot().key(), STEPS.slot().key())) {
                check(carrier.getAll(key).size() == (removed ? 1 : 0), "Removal/absence distinction: " + key);
            }
            if (removed) {
                check(new StringMapContext.Codec().isRemoval(carrier.getAll(VALUES.slot().key()).getFirst()), "Map marker");
                check(new StringSetContext.Codec().isRemoval(carrier.getAll(LABELS.slot().key()).getFirst()), "Set marker");
                check(new StringListContext.Codec().isRemoval(carrier.getAll(STEPS.slot().key()).getFirst()), "List marker");
            }
        }
    }

    private static KafkaConsumerRecord<String, String> received(ProducerRecord<String, String> sent) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(sent.topic(), 0, 0L, sent.key(), sent.value());
        sent.headers().forEach(header -> record.headers().add(header.key(), header.value().clone()));
        return new KafkaConsumerRecordImpl<>(record);
    }
}
