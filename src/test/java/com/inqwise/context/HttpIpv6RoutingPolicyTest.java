package com.inqwise.context;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.StreamResetException;
import io.vertx.core.internal.ContextInternal;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class HttpIpv6RoutingPolicyTest {
    @ParameterizedTest
    @MethodSource("resetModes")
    void ipv6Http2_conflictingHost_preservesNativeResetAndNextRequest(
            boolean multiplex, boolean populated, Vertx vertx, VertxTestContext testContext) {
        vertx.exceptionHandler(testContext::failNow);
        WebClientOptions options = new WebClientOptions().setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false);
        options.setHttp2MultiplexImplementation(multiplex);
        WebClient control = WebClient.create(vertx, options);
        WebClient client = WebClient.create(vertx, options);
        AtomicInteger received = new AtomicInteger();
        FlowId original = FlowId.builder().withId("caller-ipv6").build();
        FlowIdService codec = new FlowIdService();
        vertx.createHttpServer(new HttpServerOptions().setHttp2MultiplexImplementation(multiplex))
            .requestHandler(request -> testContext.verify(() -> {
                received.incrementAndGet();
                assertEquals(HttpVersion.HTTP_2, request.version());
                if (populated) {
                    assertEquals(original.toJson(), ((FlowId) codec.unmarshalFormString(
                        new HttpContextCarrier(request.headers()).getAll("flow_id").getFirst())).toJson());
                } else {
                    assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
                }
                new HttpContextCarrier(request.response().headers()).set("flow_id",
                    codec.marshalToString(FlowId.builder().withId("recovered-ipv6").build()));
                request.response().end("recovered");
            })).listen(0, "::1").compose(server -> {
                URI origin = URI.create("http://[::1]:" + server.actualPort());
                HttpContextTransport.install(client, origin);
                return inFlow(vertx, "ipv6-reset", ignored -> {
                    ContextInternal caller = ContextInternal.current();
                    if (populated) {
                        Storage.put("flow_id", original);
                    }
                    String conflict = "[::2]:" + server.actualPort();
                    return control.getAbs(origin + "/").putHeader("Host", conflict).send().transform(nativeResult -> {
                        assertTrue(nativeResult.failed());
                        long nativeCode = assertInstanceOf(StreamResetException.class, nativeResult.cause()).getCode();
                        assertEquals(1, nativeCode);
                        return client.getAbs(origin + "/").putHeader("Host", conflict).send().transform(result -> {
                            assertTrue(result.failed());
                            assertEquals(nativeCode, assertInstanceOf(StreamResetException.class, result.cause()).getCode());
                            assertEquals(0, received.get());
                            assertSame(caller, ContextInternal.current());
                            assertSame(populated ? original : null, Storage.get("flow_id"));
                            return client.getAbs(origin + "/").send().map(response -> {
                                assertEquals("recovered", response.bodyAsString());
                                assertEquals(1, received.get());
                                assertSame(caller, ContextInternal.current());
                                if (populated) {
                                    assertSame(original, Storage.get("flow_id"));
                                } else {
                                    assertEquals("recovered-ipv6", ((FlowId) Storage.get("flow_id")).getId());
                                }
                                return response;
                            });
                        });
                    });
                });
            }).andThen(ignored -> {
                control.close();
                client.close();
            }).onComplete(testContext.succeedingThenComplete());
    }

    @ParameterizedTest
    @MethodSource("routingModes")
    void ipv6Literal_matchingAndConflictingHost_applyPolicyWithoutBlockingPayload(
            HttpVersion version, boolean multiplex, String hostMode, boolean populated,
            Vertx vertx, VertxTestContext testContext) {
        vertx.exceptionHandler(testContext::failNow);
        WebClientOptions options = new WebClientOptions().setProtocolVersion(version).setHttp2ClearTextUpgrade(false);
        options.setHttp2MultiplexImplementation(multiplex);
        WebClient client = WebClient.create(vertx, options);
        FlowId original = FlowId.builder().withId("caller-ipv6").build();
        FlowIdService codec = new FlowIdService();
        boolean allowed = !hostMode.equals("conflicting");
        vertx.createHttpServer(new HttpServerOptions().setHttp2MultiplexImplementation(multiplex))
            .requestHandler(request -> testContext.verify(() -> {
                assertEquals(version, request.version());
                assertEquals("retained", request.getHeader("application"));
                if (allowed && populated) {
                    String encoded = new HttpContextCarrier(request.headers()).getAll("flow_id").getFirst();
                    assertEquals(original.toJson(), ((FlowId) codec.unmarshalFormString(encoded)).toJson());
                } else {
                    assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
                }
                new HttpContextCarrier(request.response().headers()).set("flow_id",
                    codec.marshalToString(FlowId.builder().withId("server-ipv6").build()));
                request.response().end("delivered");
            })).listen(0, "::1").compose(server -> {
                URI origin = URI.create("http://[::1]:" + server.actualPort());
                HttpContextTransport.install(client, origin);
                return inFlow(vertx, "ipv6-caller", ignored -> {
                    ContextInternal caller = ContextInternal.current();
                    if (populated) {
                        Storage.put("flow_id", original);
                    }
                    var request = client.getAbs(origin + "/ipv6").putHeader("application", "retained")
                        .putHeader(HttpContextCarrier.name("flow_id"), "stale-managed-header");
                    if (!hostMode.equals("native")) {
                        request.putHeader("Host", (allowed ? "[::1]:" : "[::2]:") + server.actualPort());
                    }
                    return request.send().compose(response -> {
                        assertEquals("delivered", response.bodyAsString());
                        assertEquals(version, response.version());
                        assertSame(caller, ContextInternal.current());
                        if (populated) {
                            assertSame(original, Storage.get("flow_id"));
                        } else if (allowed) {
                            assertEquals("server-ipv6", ((FlowId) Storage.get("flow_id")).getId());
                        } else {
                            assertNull(Storage.get("flow_id"));
                        }
                        var current = Storage.get("flow_id");
                        return vertx.timer(1).map(tick -> {
                            assertSame(caller, ContextInternal.current());
                            assertSame(current, Storage.get("flow_id"));
                            return tick;
                        });
                    });
                });
            }).andThen(ignored -> client.close()).onComplete(testContext.succeedingThenComplete());
    }

    private static Stream<Arguments> routingModes() {
        return Stream.of(HttpVersion.HTTP_1_1, HttpVersion.HTTP_2)
            .flatMap(version -> (version == HttpVersion.HTTP_1_1 ? Stream.of(false) : Stream.of(false, true))
                .flatMap(multiplex -> (version == HttpVersion.HTTP_1_1
                    ? Stream.of("native", "matching", "conflicting") : Stream.of("native", "matching"))
                    .flatMap(host -> Stream.of(false, true)
                        .map(populated -> Arguments.of(version, multiplex, host, populated)))));
    }

    private static Stream<Arguments> resetModes() {
        return Stream.of(false, true).flatMap(multiplex -> Stream.of(false, true)
            .map(populated -> Arguments.of(multiplex, populated)));
    }
}
