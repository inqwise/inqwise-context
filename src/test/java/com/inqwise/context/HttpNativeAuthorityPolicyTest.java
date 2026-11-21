package com.inqwise.context;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.impl.ClientPhase;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class HttpNativeAuthorityPolicyTest {
    @ParameterizedTest
    @MethodSource("authorityModes")
    void nativeAuthority_interceptorOverrides_applyPolicyWithoutBlockingPayload(
            HttpVersion version, boolean multiplex, String mode, boolean populated,
            Vertx vertx, VertxTestContext testContext) {
        vertx.exceptionHandler(testContext::failNow);
        WebClientOptions options = new WebClientOptions().setProtocolVersion(version).setHttp2ClearTextUpgrade(false);
        options.setHttp2MultiplexImplementation(multiplex);
        WebClient client = WebClient.create(vertx, options);
        AtomicReference<HostAndPort> selected = new AtomicReference<>();
        client.addInterceptor(event -> {
            if (event.phase() == ClientPhase.SEND_REQUEST) {
                event.clientRequest().authority(selected.get());
            }
            event.next();
        });
        FlowIdService codec = new FlowIdService();
        FlowId original = FlowId.builder().withId("caller").build();
        boolean allowed = mode.equals("matching");
        vertx.createHttpServer(new HttpServerOptions().setHttp2MultiplexImplementation(multiplex))
            .requestHandler(request -> testContext.verify(() -> {
                assertEquals(version, request.version());
                assertEquals(selected.get(), request.authority());
                assertEquals("retained", request.getHeader("application"));
                assertNull(request.getHeader(HttpContextCarrier.name("private")));
                if (allowed && populated) {
                    var values = new HttpContextCarrier(request.headers()).getAll("flow_id");
                    assertEquals(1, values.size());
                    assertEquals(original.toJson(), ((FlowId) codec.unmarshalFormString(values.getFirst())).toJson());
                } else {
                    assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
                }
                new HttpContextCarrier(request.response().headers()).set("flow_id",
                    codec.marshalToString(FlowId.builder().withId("server").build()));
                request.response().end("delivered");
            })).listen(0, "127.0.0.1").compose(server -> {
                selected.set(HostAndPort.create(mode.equals("host") ? "spoofed.invalid" : "127.0.0.1",
                    mode.equals("port") ? 1 : server.actualPort()));
                HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + server.actualPort()),
                    URI.create("http://" + selected.get()));
                return inFlow(vertx, "native-authority", ignored -> {
                    ContextInternal caller = ContextInternal.current();
                    if (populated) {
                        Storage.put("flow_id", original);
                    }
                    return client.get(server.actualPort(), "127.0.0.1", "/")
                        .putHeader("application", "retained")
                        .putHeader(HttpContextCarrier.name("flow_id"), "stale-managed-header")
                        .putHeader(HttpContextCarrier.name("private"), "stale-private-header")
                        .send().compose(response -> {
                            assertEquals("delivered", response.bodyAsString());
                            assertEquals(version, response.version());
                            assertSame(caller, ContextInternal.current());
                            if (populated) {
                                assertSame(original, Storage.get("flow_id"));
                            } else if (allowed) {
                                assertEquals("server", ((FlowId) Storage.get("flow_id")).getId());
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

    private static Stream<Arguments> authorityModes() {
        return Stream.of(HttpVersion.HTTP_1_1, HttpVersion.HTTP_2)
            .flatMap(version -> (version == HttpVersion.HTTP_1_1 ? Stream.of(false) : Stream.of(false, true))
                .flatMap(multiplex -> Stream.of("matching", "host", "port")
                    .flatMap(mode -> Stream.of(false, true)
                        .map(populated -> Arguments.of(version, multiplex, mode, populated)))));
    }
}
