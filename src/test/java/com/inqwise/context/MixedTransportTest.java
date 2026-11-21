package com.inqwise.context;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerConfig;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.core.net.PfxOptions;
import io.vertx.core.net.ServerSSLOptions;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientConfig;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.consumer.impl.KafkaConsumerRecordImpl;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class MixedTransportTest {
    @TempDir
    static Path directory;
    private static PfxOptions certificate;

    @BeforeAll
    static void generateCertificate() throws Exception {
        certificate = HttpTestCertificate.create(directory);
    }

    @ParameterizedTest
    @MethodSource("flowModes")
    void httpKafkaEventBus_replacementsAndRemovals_preserveTransportBoundaries(HttpVersion version, String callerState,
            boolean httpRemoval, boolean kafkaRemoval, boolean eventBusRemoval,
            Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        boolean secure = version == HttpVersion.HTTP_3;
        WebClient client = secure ? WebClient.create(vertx,
            new WebClientConfig().setVersions(version).setSsl(true).setVerifyHost(true),
            new ClientSSLOptions().setTrustOptions(new PfxOptions(certificate))) : WebClient.create(vertx);
        HttpServerConfig config = new HttpServerConfig().setVersions(version);
        config.getTcpConfig().setHost("127.0.0.1").setPort(0);
        config.getQuicConfig().setHost("127.0.0.1").setPort(0);
        HttpServer httpServer = secure
            ? vertx.createHttpServer(config, new ServerSSLOptions().setKeyCertOptions(new PfxOptions(certificate)))
            : vertx.createHttpServer(config);
        MockProducer<String, String> nativeProducer = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, nativeProducer);
        var sender = KafkaContextTransport.sender(producer, "mixed");
        List<String> visited = new CopyOnWriteArrayList<>();
        FlowId original = FlowId.builder().withId("caller").build();
        FlowId httpValue = FlowId.builder().withId("http-stage").build();
        FlowId kafkaValue = FlowId.builder().withId("kafka-stage").build();
        var consumer = vertx.eventBus().<String>consumer("mixed", message -> context.verify(() -> {
            visited.add("eventbus");
            assertEquals("payload", message.body());
            assertStored(kafkaRemoval ? null : kafkaValue.getId(), kafkaRemoval);
            if (!kafkaRemoval) {
                assertNotSame(kafkaValue, Storage.get("flow_id"));
            }
            assertNull(Storage.get("private-mixed"));
            change(eventBusRemoval, FlowId.builder().withId("eventbus-stage").build());
            message.reply("processed");
        }));

        consumer.completion().compose(ignored -> httpServer
            .requestHandler(HttpContextTransport.handler(request -> context.verify(() -> {
                assertEquals(version, request.version());
                visited.add("http");
                ContextInternal httpScope = ContextInternal.current();
                assertStored(callerState.equals("value") ? "caller" : null, callerState.equals("removed"));
                if (callerState.equals("value")) {
                    assertNotSame(original, Storage.get("flow_id"));
                }
                change(httpRemoval, httpValue);
                Storage.put("private-mixed", original);
                sender.send(KafkaProducerRecord.create("mixed", "key", "payload")).compose(ack -> {
                    assertSame(httpScope, ContextInternal.current());
                    assertSame(httpRemoval ? null : httpValue, Storage.get("flow_id"));
                    var sent = nativeProducer.history().getFirst();
                    ConsumerRecord<String, String> received = new ConsumerRecord<>(sent.topic(), 0, 0L, sent.key(), sent.value());
                    sent.headers().forEach(header -> received.headers().add(header.key(), header.value().clone()));
                    Promise<Void> processed = Promise.promise();
                    Handler<KafkaConsumerRecord<String, String>> handler = KafkaContextTransport.handler(message -> {
                        try {
                            visited.add("kafka");
                            ContextInternal kafkaScope = ContextInternal.current();
                            assertNotSame(httpScope, kafkaScope);
                            assertNull(Storage.get("private-mixed"));
                            assertStored(httpRemoval ? null : "http-stage", httpRemoval);
                            if (!httpRemoval) {
                                assertNotSame(httpValue, Storage.get("flow_id"));
                            }
                            change(kafkaRemoval, kafkaValue);
                            Storage.put("private-mixed", kafkaValue);
                            vertx.eventBus().<String>request("mixed", message.value()).compose(reply -> {
                                visited.add("kafka-return");
                                assertSame(kafkaScope, ContextInternal.current());
                                assertEquals("processed", reply.body());
                                assertStored(kafkaRemoval ? null : "kafka-stage", kafkaRemoval);
                                assertSame(kafkaRemoval ? null : kafkaValue, Storage.get("flow_id"));
                                String returned = reply.headers().get("flow_id");
                                if (eventBusRemoval) {
                                    assertTrue(new FlowIdService().isRemoval(returned));
                                } else {
                                    assertEquals("eventbus-stage", ((FlowId) new FlowIdService().unmarshalFormString(returned)).getId());
                                }
                                return vertx.timer(1);
                            }).map(tick -> {
                                assertSame(kafkaScope, ContextInternal.current());
                                assertStored(kafkaRemoval ? null : "kafka-stage", kafkaRemoval);
                                return (Void) null;
                            }).onComplete(processed);
                        } catch (Throwable failure) {
                            processed.tryFail(failure);
                        }
                    }, "mixed");
                    handler.handle(new KafkaConsumerRecordImpl<>(received));
                    assertSame(httpScope, ContextInternal.current());
                    return ContextPropagation.bind(processed.future());
                }).onSuccess(done -> context.verify(() -> {
                    visited.add("http-return");
                    assertSame(httpScope, ContextInternal.current());
                    assertStored(httpRemoval ? null : "http-stage", httpRemoval);
                    assertSame(httpRemoval ? null : httpValue, Storage.get("flow_id"));
                    assertSame(original, Storage.get("private-mixed"));
                    request.response().end("done");
                })).onFailure(context::failNow);
            }))).listen()).compose(server -> {
                HttpContextTransport.install(client, URI.create((secure ? "https" : "http") + "://127.0.0.1:" + server.actualPort()));
                return inFlow(vertx, "caller", ignored -> {
                    ContextInternal caller = ContextInternal.current();
                    if (callerState.equals("value")) {
                        Storage.put("flow_id", original);
                    } else if (callerState.equals("removed")) {
                        Storage.remove("flow_id");
                    }
                    return client.get(server.actualPort(), "127.0.0.1", "/").send().map(response -> {
                        visited.add("client-return");
                        assertSame(caller, ContextInternal.current());
                        assertEquals(version, response.version());
                        assertEquals("done", response.bodyAsString());
                        assertEquals(List.of("http", "kafka", "eventbus", "kafka-return", "http-return", "client-return"), visited);
                        String returned = new HttpContextCarrier(response.headers()).getAll("flow_id").getFirst();
                        if (httpRemoval) {
                            assertTrue(new FlowIdService().isRemoval(returned));
                        } else {
                            assertEquals("http-stage", ((FlowId) new FlowIdService().unmarshalFormString(returned)).getId());
                        }
                        if (callerState.equals("value")) {
                            assertSame(original, Storage.get("flow_id"));
                        } else {
                            boolean removed = callerState.equals("removed") || httpRemoval;
                            assertStored(removed ? null : "http-stage", removed);
                        }
                        return response;
                    });
                });
            }).eventually(producer::close).eventually(consumer::unregister)
            .andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    private static void change(boolean removal, FlowId value) {
        if (removal) {
            Storage.remove("flow_id");
        } else {
            Storage.put("flow_id", value);
        }
    }

    private static void assertStored(String expectedId, boolean removal) {
        if (expectedId != null) {
            assertEquals(expectedId, ((FlowId) Storage.get("flow_id")).getId());
        } else {
            assertNull(Storage.get("flow_id"));
            MultiMap headers = MultiMap.caseInsensitiveMultiMap();
            ContextTransport.writeTo(new HttpContextCarrier(headers));
            if (removal) {
                assertTrue(new FlowIdService().isRemoval(new HttpContextCarrier(headers).getAll("flow_id").getFirst()));
            } else {
                assertFalse(HttpContextCarrier.hasMetadata(headers));
            }
        }
    }

    private static Stream<Arguments> flowModes() {
        return Stream.of(HttpVersion.HTTP_1_1, HttpVersion.HTTP_3)
            .flatMap(version -> Stream.of("value", "removed", "empty").flatMap(caller -> Stream.of(false, true)
                .flatMap(http -> Stream.of(false, true).flatMap(kafka -> Stream.of(false, true)
                    .map(eventbus -> Arguments.of(version, caller, http, kafka, eventbus))))));
    }
}
