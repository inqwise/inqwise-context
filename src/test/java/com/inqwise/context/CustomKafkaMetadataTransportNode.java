package com.inqwise.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.inqwise.context.CustomMetadataTestProvider.Value;
import com.inqwise.context.data.FlowId;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
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

import static com.inqwise.context.CustomMetadataTestProvider.KEY;
import static com.inqwise.context.TransportClasspathTestSupport.check;

/** Forked Kafka-only application using custom codec and configuration SPI providers. */
public final class CustomKafkaMetadataTransportNode {
    private CustomKafkaMetadataTransportNode() {
    }

    /** Exercises asynchronous custom state changes across two consumer scopes. */
    public static void main(String[] args) throws Exception {
        TransportClasspathTestSupport.requireAbsent("io.vertx.ext.web.client.WebClient", "org.junit.jupiter.api.Assertions");
        check(ContextTransport.defaultConfiguration().includedKeys().equals(Set.of("flow_id", KEY)), "Configuration SPI selection");
        FlowId originalFlow = FlowId.builder().withId("producer-flow").build();
        FlowId downstreamFlow = FlowId.builder().withId("consumer-flow").build();
        Value original = new Value("producer:\uD83D\uDE80");
        Value changed = new Value("consumer:\u0394");
        Vertx vertx = Vertx.vertx();
        MockProducer<String, String> nativeProducer = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, nativeProducer);
        var sender = KafkaContextTransport.sender(producer, "source", "forwarded");
        try {
            for (String state : List.of("value", "removed", "empty")) {
                for (boolean removal : List.of(false, true)) {
                    vertx.deployVerticle(new VerticleBase() {
                        @Override
                        public Future<?> start() {
                            ContextInternal caller = ContextInternal.current();
                            Storage.put("flow_id", originalFlow);
                            if (state.equals("value")) {
                                Storage.put(KEY, original);
                            } else if (state.equals("removed")) {
                                Storage.remove(KEY);
                            }
                            Value localOnly = new Value("producer-private");
                            Storage.put("private", localOnly);
                            return sender.send(KafkaProducerRecord.create("source", "key", "payload")).compose(ack -> {
                                check(ContextInternal.current() == caller, "Producer acknowledgment context");
                                Promise<Void> processed = Promise.promise();
                                Handler<KafkaConsumerRecord<String, String>> consumer = KafkaContextTransport.handler(message -> {
                                    try {
                                        ContextInternal scope = ContextInternal.current();
                                        check(scope != caller, "First consumer context");
                                        check(message.value().equals("payload"), "Source payload");
                                        FlowId incomingFlow = (FlowId) Storage.get("flow_id");
                                        check(incomingFlow != originalFlow && incomingFlow.toJson().equals(originalFlow.toJson()),
                                            "Imported FlowId identity");
                                        assertState(state.equals("value") ? original : null, state.equals("removed"));
                                        if (state.equals("value")) {
                                            check(Storage.get(KEY) != original, "Custom value was not decoded");
                                        }
                                        check(Storage.get("private") == null, "Producer private storage leaked");
                                        vertx.timer(1).compose(tick -> {
                                            check(ContextInternal.current() == scope, "Consumer timer context");
                                            if (removal) {
                                                Storage.remove(KEY);
                                            } else {
                                                Storage.put(KEY, changed);
                                            }
                                            Storage.put("flow_id", downstreamFlow);
                                            Storage.put("private", new Value("consumer-private"));
                                            return sender.send(KafkaProducerRecord.create("forwarded", "key", "derived"));
                                        }).map(forwardedAck -> {
                                            check(ContextInternal.current() == scope, "Forward acknowledgment context");
                                            Handler<KafkaConsumerRecord<String, String>> next = KafkaContextTransport.handler(later -> {
                                                ContextInternal nextScope = ContextInternal.current();
                                                check(nextScope != scope && nextScope != caller, "Second consumer context");
                                                check(later.value().equals("derived"), "Forwarded payload");
                                                check(Storage.get("private") == null, "Consumer private storage leaked");
                                                FlowId forwardedFlow = (FlowId) Storage.get("flow_id");
                                                check(forwardedFlow != downstreamFlow && forwardedFlow.toJson().equals(downstreamFlow.toJson()),
                                                    "Forwarded FlowId identity");
                                                assertState(removal ? null : changed, removal);
                                                if (!removal) {
                                                    check(Storage.get(KEY) != changed, "Forwarded custom value was not decoded");
                                                }
                                                Storage.put(KEY, new Value("second-consumer-local"));
                                                Storage.remove("flow_id");
                                            }, "forwarded");
                                            next.handle(received(nativeProducer.history().getLast()));
                                            check(ContextInternal.current() == scope, "First consumer context was not restored");
                                            check(Storage.get("flow_id") == downstreamFlow, "Second consumer changed first FlowId");
                                            check(Storage.get(KEY) == (removal ? null : changed), "Second consumer changed first custom state");
                                            assertState(removal ? null : changed, removal);
                                            return (Void) null;
                                        }).onComplete(processed);
                                    } catch (Throwable failure) {
                                        processed.tryFail(failure);
                                    }
                                }, "source");
                                consumer.handle(received(nativeProducer.history().getLast()));
                                check(ContextInternal.current() == caller, "Producer context was not restored");
                                return ContextPropagation.bind(processed.future());
                            }).map(done -> {
                                check(ContextInternal.current() == caller, "Producer completion context");
                                check(Storage.get("flow_id") == originalFlow, "Consumer changes returned through acknowledgment");
                                check(Storage.get("private") == localOnly, "Producer private storage changed");
                                check(Storage.get(KEY) == (state.equals("value") ? original : null), "Consumer custom changes returned");
                                assertState(state.equals("value") ? original : null, state.equals("removed"));
                                return done;
                            });
                        }
                    }).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                }
            }
            check(nativeProducer.history().size() == 12, "Missing source or forwarded records");
            check(CustomMetadataTestProvider.Configuration.calls.get() == 1, "Configuration SPI evaluated more than once");
            check(CustomMetadataTestProvider.constructed.get() == 1, "Codec SPI instantiated more than once");
            System.out.println("CUSTOM_KAFKA_METADATA_OK");
        } finally {
            try {
                producer.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static KafkaConsumerRecord<String, String> received(ProducerRecord<String, String> sent) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(sent.topic(), 0, 0L, sent.key(), sent.value());
        sent.headers().forEach(header -> record.headers().add(header.key(), header.value().clone()));
        return new KafkaConsumerRecordImpl<>(record);
    }

    private static void assertState(Value expected, boolean removal) {
        check(expected == null ? Storage.get(KEY) == null : expected.equals(Storage.get(KEY)), "Custom metadata state");
        List<KafkaHeader> headers = new ArrayList<>();
        ContextTransport.writeTo(new KafkaContextCarrier(() -> headers));
        List<String> values = new KafkaContextCarrier(() -> headers).getAll(KEY);
        if (removal) {
            check(values.equals(List.of(CustomMetadataTestProvider.REMOVAL)), "Stored removal marker missing");
        } else if (expected == null) {
            check(values.isEmpty(), "Unexpected removal marker");
        } else {
            check(values.equals(List.of(CustomMetadataTestProvider.VALUE + expected.text())), "Stored replacement not exported");
        }
    }
}
