package com.inqwise.context;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.consumer.impl.KafkaConsumerRecordImpl;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import io.vertx.kafka.client.producer.KafkaHeader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;

import static com.inqwise.context.TransportClasspathTestSupport.check;

/** Forked Kafka application with no WebClient on its runtime classpath. */
public final class KafkaOnlyTransportNode {
    private KafkaOnlyTransportNode() {
    }

    /** Exercises Kafka propagation with only the Kafka client installed. */
    public static void main(String[] args) throws Exception {
        TransportClasspathTestSupport.requireAbsent("io.vertx.ext.web.client.WebClient", "org.junit.jupiter.api.Assertions");
        Vertx vertx = Vertx.vertx();
        MockProducer<String, String> nativeProducer = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, nativeProducer);
        var sender = KafkaContextTransport.sender(producer, "orders");
        try {
            for (String state : List.of("value", "removed", "empty")) {
                vertx.deployVerticle(new VerticleBase() {
                    @Override
                    public Future<?> start() {
                        ContextInternal caller = ContextInternal.current();
                        FlowId original = FlowId.builder().withId("caller").build();
                        if (state.equals("value")) {
                            Storage.put("flow_id", original);
                        } else if (state.equals("removed")) {
                            Storage.remove("flow_id");
                        }
                        return sender.send(KafkaProducerRecord.create("orders", "key", "payload")).compose(ack -> {
                            check(ContextInternal.current() == caller, "Kafka acknowledgment context");
                            var sent = nativeProducer.history().getLast();
                            ConsumerRecord<String, String> received = new ConsumerRecord<>(sent.topic(), 0, 0L, sent.key(), sent.value());
                            sent.headers().forEach(header -> received.headers().add(header.key(), header.value()));
                            Promise<Void> processed = Promise.promise();
                            Handler<KafkaConsumerRecord<String, String>> handler = KafkaContextTransport.handler(message -> {
                                ContextInternal scope = ContextInternal.current();
                                check(scope != caller, "Kafka consumer scope");
                                check(message.value().equals("payload"), "Kafka payload");
                                if (state.equals("value")) {
                                    FlowId incoming = (FlowId) Storage.get("flow_id");
                                    check(incoming != original && incoming.toJson().equals(original.toJson()), "Kafka imported value");
                                } else {
                                    check(Storage.get("flow_id") == null, "Unexpected Kafka value");
                                    List<String> values = new KafkaContextCarrier(message::headers).getAll("flow_id");
                                    check(state.equals("removed") ? new FlowIdService().isRemoval(values.getFirst()) : values.isEmpty(),
                                        "Kafka removal or empty metadata");
                                    List<KafkaHeader> forwarded = new ArrayList<>();
                                    ContextTransport.writeTo(new KafkaContextCarrier(() -> forwarded));
                                    check(new KafkaContextCarrier(() -> forwarded).getAll("flow_id").equals(values),
                                        "Kafka imported removal marker");
                                }
                                FlowId changed = FlowId.builder().withId("consumer-local").build();
                                Storage.put("flow_id", changed);
                                vertx.timer(1).onComplete(result -> {
                                    try {
                                        check(result.succeeded() && ContextInternal.current() == scope, "Kafka timer context");
                                        check(Storage.get("flow_id") == changed, "Kafka timer value");
                                        processed.complete();
                                    } catch (Throwable failure) {
                                        processed.tryFail(failure);
                                    }
                                });
                            }, "orders");
                            handler.handle(new KafkaConsumerRecordImpl<>(received));
                            return ContextPropagation.bind(processed.future());
                        }).map(done -> {
                            check(ContextInternal.current() == caller, "Kafka return context");
                            check(state.equals("value") ? Storage.get("flow_id") == original : Storage.get("flow_id") == null,
                                "Consumer changes leaked to producer");
                            return done;
                        });
                    }
                }).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            System.out.println("KAFKA_ONLY_TRANSPORT_OK");
        } finally {
            try {
                producer.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }
}
