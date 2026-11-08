package com.inqwise.context;

import java.util.List;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.inqwise.context.ClientContextTestSupport.assertContext;
import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(10)
class KafkaClientContextTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void send_successOrFailure_preservesCallerInstance(boolean fail, Vertx vertx, VertxTestContext context) {
        MockProducer<String, String> nativeProducer = mockProducer();
        KafkaException failure = new KafkaException("controlled failure");
        if (fail) {
            nativeProducer.sendException = failure;
        }
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, nativeProducer);
        inFlow(vertx, "caller", item -> producer.send(KafkaProducerRecord.create("topic", "key", "value"))
            .transform(result -> {
                assertContext(item);
                assertEquals(fail, result.failed());
                if (fail) {
                    assertSame(failure, result.cause());
                } else {
                    assertEquals("topic", result.result().getTopic());
                    assertEquals("value", nativeProducer.history().getFirst().value());
                }
                return Future.succeededFuture(item);
            })).eventually(producer::close).onComplete(context.succeedingThenComplete());
    }

    @Test
    void send_sharedProducer_isolatesConcurrentCallerInstances(Vertx vertx, VertxTestContext context) {
        AtomicInteger sent = new AtomicInteger();
        MockProducer<String, String> delayed = new MockProducer<>(false, null, new StringSerializer(), new StringSerializer()) {
            @Override
            public synchronized java.util.concurrent.Future<RecordMetadata> send(ProducerRecord<String, String> record,
                    Callback callback) {
                java.util.concurrent.Future<RecordMetadata> result = super.send(record, callback);
                if (sent.incrementAndGet() == 2) {
                    // Both calls must be outstanding before either acknowledgment is delivered.
                    completeNext();
                    completeNext();
                }
                return result;
            }
        };
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, delayed);
        Future<Void> first = inFlow(vertx, "first", item -> producer
            .send(KafkaProducerRecord.create("topic", item.id(), item.id())).map(metadata -> {
                assertContext(item);
                return metadata;
            }));
        Future<Void> second = inFlow(vertx, "second", item -> producer
            .send(KafkaProducerRecord.create("topic", item.id(), item.id())).map(metadata -> {
                assertContext(item);
                return metadata;
            }));
        Future.all(List.of(first, second)).eventually(producer::close)
            .onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void poll_sharedConsumerWithAdapter_preservesInstanceInCallAndReturn(
            boolean fail, Vertx vertx, VertxTestContext context) {
        MockConsumer<String, String> nativeConsumer = new MockConsumer<>("earliest");
        TopicPartition partition = new TopicPartition("topic", 0);
        nativeConsumer.updateBeginningOffsets(Map.of(partition, 0L));
        KafkaException failure = new KafkaException("controlled polling failure");
        KafkaConsumer<String, String> consumer = KafkaConsumer.create(vertx, nativeConsumer);
        inFlow(vertx, "caller", item -> ContextPropagation.bind(consumer
            .assign(new io.vertx.kafka.client.common.TopicPartition("topic", 0))).compose(ignored -> {
                assertContext(item);
                nativeConsumer.addRecord(new ConsumerRecord<>("topic", 0, 0L, "key", "value"));
                if (fail) {
                    nativeConsumer.setPollException(failure);
                }
                return ContextPropagation.bind(consumer.poll(Duration.ofMillis(10)));
            }).transform(result -> {
                assertContext(item);
                assertEquals(fail, result.failed());
                if (fail) {
                    assertSame(failure, result.cause());
                } else {
                    assertEquals(1, result.result().size());
                    assertEquals("value", result.result().recordAt(0).value());
                }
                return Future.succeededFuture(item);
            })).eventually(consumer::close).onComplete(context.succeedingThenComplete());
    }

    private MockProducer<String, String> mockProducer() {
        return new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
    }

    @Test
    void send_failedAcknowledgment_preservesInstanceInFailureCallback(Vertx vertx, VertxTestContext context) {
        KafkaException failure = new KafkaException("controlled acknowledgment failure");
        MockProducer<String, String> nativeProducer = new MockProducer<>(
                false, null, new StringSerializer(), new StringSerializer()) {
            @Override
            public synchronized java.util.concurrent.Future<RecordMetadata> send(ProducerRecord<String, String> record,
                    Callback callback) {
                java.util.concurrent.Future<RecordMetadata> result = super.send(record, callback);
                errorNext(failure);
                return result;
            }
        };
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, nativeProducer);
        inFlow(vertx, "caller", item -> producer.send(KafkaProducerRecord.create("topic", "key", "value"))
            .transform(result -> {
                assertContext(item);
                assertTrue(result.failed());
                assertSame(failure, result.cause());
                return Future.succeededFuture(item);
            })).eventually(producer::close).onComplete(context.succeedingThenComplete());
    }
}
