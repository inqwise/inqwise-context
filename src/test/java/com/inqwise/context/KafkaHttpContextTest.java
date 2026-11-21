package com.inqwise.context;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.internal.ContextInternal;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientSession;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class KafkaHttpContextTest {
    private record Incoming(HttpServerRequest request, ContextInternal scope, FlowId imported) {
    }

    @ParameterizedTest
    @MethodSource("flowModes")
    void kafkaHttp_overlappingRecords_preserveCurrentConsumerStateAcrossReplies(
            ThreadingModel model, boolean removal, boolean allowed, boolean remoteRemoval,
            Vertx vertx, VertxTestContext testContext) {
        vertx.exceptionHandler(testContext::failNow);
        WebClient base = WebClient.create(vertx);
        WebClientSession client = WebClientSession.create(base);
        Map<String, Incoming> incoming = new ConcurrentHashMap<>();
        Map<String, ContextInternal> scopes = new ConcurrentHashMap<>();
        AtomicInteger completed = new AtomicInteger();
        Promise<Void> processed = Promise.promise();
        vertx.createHttpServer().requestHandler(HttpContextTransport.handler(request -> testContext.verify(() -> {
            String id = request.path().substring(1);
            assertEquals("retained", request.getHeader("application"));
            assertNull(request.getHeader(HttpContextCarrier.name("private")));
            assertNull(Storage.get("private-kafka-http"));
            FlowId imported = (FlowId) Storage.get("flow_id");
            if (allowed && !removal) {
                assertEquals("consumer-" + id, imported.getId());
            } else {
                assertNull(imported);
            }
            assertEquals(allowed && removal, DefaultStorage.isRemoved(
                DefaultStorage.getEntry(ContextInternal.current(), "flow_id")));
            incoming.put(id, new Incoming(request, ContextInternal.current(), imported));
            if (incoming.size() == 2) {
                assertNotSame(incoming.get("first").scope(), incoming.get("second").scope());
                for (String key : List.of("second", "first")) {
                    Incoming entry = incoming.get(key);
                    entry.scope().runOnContext(ignored -> testContext.verify(() -> {
                        assertSame(entry.scope(), ContextInternal.current());
                        assertSame(entry.imported(), Storage.get("flow_id"));
                        change(remoteRemoval, FlowId.builder().withId("http-" + key).build());
                        entry.request().response().end(key);
                    }));
                }
            }
        }))).listen(0, "127.0.0.1").compose(server -> {
            HttpContextTransport.install(client, allowed
                ? new URI[] {URI.create("http://127.0.0.1:" + server.actualPort())} : new URI[0]);
            client.addHeader("application", "retained");
            client.addHeader(HttpContextCarrier.name("flow_id"), "stale-managed-header");
            client.addHeader(HttpContextCarrier.name("private"), "stale-private-header");
            return vertx.deployVerticle(new VerticleBase() {
                @Override
                public Future<?> start() {
                    ContextInternal parent = ContextInternal.current();
                    FlowId original = FlowId.builder().withId("consumer-parent").build();
                    Storage.put("flow_id", original);
                    MockConsumer<String, String> nativeConsumer = new MockConsumer<>("earliest");
                    nativeConsumer.updateBeginningOffsets(Map.of(new TopicPartition("orders", 0), 0L));
                    KafkaConsumer<String, String> consumer = KafkaConsumer.create(vertx, nativeConsumer);
                    consumer.exceptionHandler(processed::tryFail);
                    consumer.handler(KafkaContextTransport.handler(message -> testContext.verify(() -> {
                        String id = message.key();
                        ContextInternal scope = ContextInternal.current();
                        assertEquals(model, scope.threadingModel());
                        assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                        scopes.put(id, scope);
                        if (id.equals("first")) {
                            assertEquals("source-first", ((FlowId) Storage.get("flow_id")).getId());
                        } else {
                            assertNull(Storage.get("flow_id"));
                            assertFalse(DefaultStorage.isRemoved(DefaultStorage.getEntry(scope, "flow_id")));
                        }
                        FlowId current = FlowId.builder().withId("consumer-" + id).build();
                        Storage.put("private-kafka-http", current);
                        vertx.timer(1).compose(tick -> {
                            assertSame(scope, ContextInternal.current());
                            change(removal, current);
                            return client.get(server.actualPort(), "127.0.0.1", "/" + id).send();
                        }).compose(response -> {
                            assertSame(scope, ContextInternal.current());
                            assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                            assertEquals(id, response.bodyAsString());
                            String returned = new HttpContextCarrier(response.headers()).getAll("flow_id").getFirst();
                            FlowIdService codec = new FlowIdService();
                            if (remoteRemoval) {
                                assertTrue(codec.isRemoval(returned));
                            } else {
                                assertEquals("http-" + id, ((FlowId) codec.unmarshalFormString(returned)).getId());
                            }
                            assertState(scope, removal, current);
                            return vertx.timer(1);
                        }).onComplete(result -> testContext.verify(() -> {
                            assertTrue(result.succeeded());
                            assertSame(scope, ContextInternal.current());
                            assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                            assertState(scope, removal, current);
                            assertSame(current, Storage.get("private-kafka-http"));
                            if (completed.incrementAndGet() == 2) {
                                assertEquals(2, scopes.values().stream().distinct().count());
                                processed.complete();
                            }
                        }));
                    }), "orders"));
                    return consumer.assign(new io.vertx.kafka.client.common.TopicPartition("orders", 0)).compose(ignored -> {
                        nativeConsumer.schedulePollTask(() -> {
                            int offset = 0;
                            for (String id : List.of("first", "second")) {
                                KafkaProducerRecord<String, String> record = KafkaProducerRecord.create("orders", id, "payload", 0);
                                if (id.equals("first")) {
                                    new KafkaContextCarrier(record::headers).set("flow_id", new FlowIdService()
                                        .marshalToString(FlowId.builder().withId("source-first").build()));
                                }
                                var sent = record.record();
                                nativeConsumer.addRecord(new ConsumerRecord<>(sent.topic(), 0, offset++, 0,
                                    TimestampType.CREATE_TIME, 0, 0, sent.key(), sent.value(), sent.headers(), Optional.empty()));
                            }
                        });
                        return ContextPropagation.bind(processed.future());
                    }).map(ignored -> {
                        assertSame(parent, ContextInternal.current());
                        assertSame(original, Storage.get("flow_id"));
                        assertNull(Storage.get("private-kafka-http"));
                        return ignored;
                    }).eventually(consumer::close);
                }
            }, new DeploymentOptions().setThreadingModel(model));
        }).andThen(ignored -> base.close()).onComplete(testContext.succeedingThenComplete());
    }

    private static void change(boolean removal, FlowId value) {
        if (removal) {
            Storage.remove("flow_id");
        } else {
            Storage.put("flow_id", value);
        }
    }

    private static void assertState(ContextInternal scope, boolean removal, FlowId current) {
        assertSame(removal ? null : current, Storage.get("flow_id"));
        assertEquals(removal, DefaultStorage.isRemoved(DefaultStorage.getEntry(scope, "flow_id")));
    }

    private static Stream<Arguments> flowModes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of(false, true).flatMap(removal -> Stream.of(false, true)
                .flatMap(allowed -> Stream.of(false, true)
                    .map(remote -> Arguments.of(model, removal, allowed, remote)))));
    }
}
