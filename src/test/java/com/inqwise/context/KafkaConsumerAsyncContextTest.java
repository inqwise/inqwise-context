package com.inqwise.context;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.impl.KafkaConsumerRecordImpl;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class KafkaConsumerAsyncContextTest {
    @ParameterizedTest
    @MethodSource("completionModes")
    void streamingConsumer_reversedForeignCompletions_isolateStateAndForwardCurrentMetadata(
            ThreadingModel model, boolean removal, boolean failure, boolean forwardAllowed,
            Vertx vertx, VertxTestContext testContext) {
        vertx.exceptionHandler(testContext::failNow);
        MockProducer<String, String> nativeProducer = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, nativeProducer);
        var sender = KafkaContextTransport.sender(producer, forwardAllowed ? new String[] {"forwarded"} : new String[0]);
        List<String> ids = List.of("first", "second", "empty");
        Map<String, CompletableFuture<String>> gates = new ConcurrentHashMap<>();
        Map<String, Promise<Void>> finished = new ConcurrentHashMap<>();
        Map<String, ContextInternal> scopes = new ConcurrentHashMap<>();
        List<String> completed = new CopyOnWriteArrayList<>();
        ids.forEach(id -> {
            gates.put(id, new CompletableFuture<>());
            finished.put(id, Promise.promise());
        });
        Promise<Void> received = Promise.promise();
        Promise<Void> allDone = Promise.promise();
        AtomicInteger count = new AtomicInteger();
        IllegalStateException expected = new IllegalStateException("Controlled foreign completion failure");
        Future<String> deployment = vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                ContextInternal parent = ContextInternal.current();
                FlowId original = FlowId.builder().withId("consumer-parent").build();
                Storage.put("flow_id", original);
                MockConsumer<String, String> nativeConsumer = new MockConsumer<>("earliest");
                nativeConsumer.updateBeginningOffsets(Map.of(new TopicPartition("orders", 0), 0L));
                KafkaConsumer<String, String> consumer = KafkaConsumer.create(vertx, nativeConsumer);
                consumer.exceptionHandler(allDone::tryFail);
                consumer.handler(KafkaContextTransport.handler(message -> testContext.verify(() -> {
                    String id = message.key();
                    assertEquals("payload", message.value());
                    ContextInternal scope = ContextInternal.current();
                    assertEquals(model, scope.threadingModel());
                    assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                    assertTrue(scope.isDuplicate());
                    scopes.put(id, scope);
                    if (id.equals("empty")) {
                        assertNull(Storage.get("flow_id"));
                    } else {
                        assertEquals(id, ((FlowId) Storage.get("flow_id")).getId());
                    }
                    FlowId current = FlowId.builder().withId("local-" + id).build();
                    if (removal) {
                        Storage.remove("flow_id");
                    } else {
                        Storage.put("flow_id", current);
                    }
                    ContextPropagation.bind(gates.get(id)).onComplete(result -> testContext.verify(() -> {
                        assertSame(scope, ContextInternal.current());
                        assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                        if (failure) {
                            assertSame(expected, result.cause());
                        } else {
                            assertTrue(result.succeeded());
                            assertEquals(id, result.result());
                        }
                        assertState(scope, removal, current);
                        vertx.timer(1).onComplete(tick -> testContext.verify(() -> {
                            assertTrue(tick.succeeded());
                            assertSame(scope, ContextInternal.current());
                            assertState(scope, removal, current);
                            KafkaProducerRecord<String, String> forwarded = KafkaProducerRecord.create("forwarded", id, "derived", 0);
                            forwarded.addHeader("application", "retained");
                            forwarded.addHeader(KafkaContextCarrier.name("flow_id"), "stale-managed-header");
                            forwarded.addHeader(KafkaContextCarrier.name("private"), "stale-private-header");
                            sender.send(forwarded).onComplete(ack -> testContext.verify(() -> {
                                assertTrue(ack.succeeded());
                                assertEquals("forwarded", ack.result().getTopic());
                                assertSame(scope, ContextInternal.current());
                                assertState(scope, removal, current);
                                assertEquals("stale-managed-header", forwarded.headers().stream()
                                    .filter(header -> header.key().equals(KafkaContextCarrier.name("flow_id")))
                                    .findFirst().orElseThrow().value().toString());
                                var sent = nativeProducer.history().stream().filter(record -> record.key().equals(id))
                                    .findFirst().orElseThrow();
                                var downstream = new KafkaConsumerRecordImpl<>(new ConsumerRecord<>(sent.topic(), 0, 0, 0,
                                    TimestampType.CREATE_TIME, 0, 0, sent.key(), sent.value(), sent.headers(), Optional.empty()));
                                assertEquals("retained", downstream.headers().stream()
                                    .filter(header -> header.key().equals("application")).findFirst().orElseThrow().value().toString());
                                assertEquals(forwardAllowed ? 1 : 0,
                                    new KafkaContextCarrier(downstream::headers).getAll("flow_id").size());
                                assertTrue(new KafkaContextCarrier(downstream::headers).getAll("private").isEmpty());
                                KafkaContextTransport.<String, String>handler(next -> {
                                    assertNotSame(scope, ContextInternal.current());
                                    assertEquals("derived", next.value());
                                    if (forwardAllowed && !removal) {
                                        FlowId imported = (FlowId) Storage.get("flow_id");
                                        assertNotSame(current, imported);
                                        assertEquals(current.toJson(), imported.toJson());
                                    } else {
                                        assertNull(Storage.get("flow_id"));
                                    }
                                    assertEquals(forwardAllowed && removal, DefaultStorage.isRemoved(
                                        DefaultStorage.getEntry(ContextInternal.current(), "flow_id")));
                                    Storage.put("flow_id", FlowId.builder().withId("downstream-" + id).build());
                                }, "forwarded").handle(downstream);
                                assertSame(scope, ContextInternal.current());
                                assertState(scope, removal, current);
                                completed.add(id);
                                finished.get(id).complete();
                            }));
                        }));
                    }));
                    if (count.incrementAndGet() == ids.size()) {
                        received.complete();
                    }
                }), "orders"));
                return consumer.assign(new io.vertx.kafka.client.common.TopicPartition("orders", 0)).compose(ignored -> {
                    nativeConsumer.schedulePollTask(() -> {
                        for (int offset = 0; offset < ids.size(); offset++) {
                            String id = ids.get(offset);
                            KafkaProducerRecord<String, String> record = KafkaProducerRecord.create("orders", id, "payload", 0);
                            if (!id.equals("empty")) {
                                new KafkaContextCarrier(record::headers).set("flow_id", new FlowIdService()
                                    .marshalToString(FlowId.builder().withId(id).build()));
                            }
                            var sent = record.record();
                            nativeConsumer.addRecord(new ConsumerRecord<>(sent.topic(), 0, offset, 0,
                                TimestampType.CREATE_TIME, 0, 0,
                                sent.key(), sent.value(), sent.headers(), Optional.empty()));
                        }
                    });
                    return ContextPropagation.bind(allDone.future());
                }).map(ignored -> {
                    assertSame(parent, ContextInternal.current());
                    assertSame(original, Storage.get("flow_id"));
                    return ignored;
                }).eventually(consumer::close);
            }
        }, new DeploymentOptions().setThreadingModel(model));
        received.future().compose(ignored -> {
            assertEquals(3, scopes.values().stream().distinct().count());
            Future<Void> sequence = Future.succeededFuture();
            for (String id : List.of("empty", "second", "first")) {
                sequence = sequence.compose(done -> {
                    Thread.ofPlatform().start(() -> {
                        try {
                            assertNull(Vertx.currentContext());
                            if (failure) {
                                gates.get(id).completeExceptionally(expected);
                            } else {
                                gates.get(id).complete(id);
                            }
                        } catch (Throwable error) {
                            allDone.tryFail(error);
                        }
                    });
                    return finished.get(id).future();
                });
            }
            return sequence.map(done -> {
                assertEquals(List.of("empty", "second", "first"), completed);
                return done;
            });
        }).onComplete(allDone);
        deployment.eventually(producer::close).onComplete(testContext.succeedingThenComplete());
    }

    private static void assertState(ContextInternal scope, boolean removal, FlowId current) {
        if (removal) {
            assertNull(Storage.get("flow_id"));
            assertTrue(DefaultStorage.isRemoved(DefaultStorage.getEntry(scope, "flow_id")));
        } else {
            assertSame(current, Storage.get("flow_id"));
        }
    }

    private static Stream<Arguments> completionModes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of(false, true).flatMap(removal -> Stream.of(false, true)
                .flatMap(failure -> Stream.of(false, true)
                    .map(allowed -> Arguments.of(model, removal, failure, allowed)))));
    }
}
