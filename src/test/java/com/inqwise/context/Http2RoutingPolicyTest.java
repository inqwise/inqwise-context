package com.inqwise.context;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.internal.ContextInternal;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class Http2RoutingPolicyTest {
    private enum Routing {
        DIRECT, VIRTUAL_MATCHING, VIRTUAL_OTHER
    }

    @ParameterizedTest
    @MethodSource("routingModes")
    void client_virtualHost_enforcesRoutingPolicyForHttp2Authority(
            boolean multiplex, Routing routing, String state, boolean remoteRemoval,
            Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient client = client(vertx, multiplex);
        FlowIdService codec = new FlowIdService();
        boolean allowed = routing == Routing.DIRECT;
        String host = routing == Routing.VIRTUAL_OTHER ? "trusted.invalid" : "127.0.0.1";
        vertx.createHttpServer(new HttpServerOptions().setHttp2MultiplexImplementation(multiplex))
            .requestHandler(request -> context.verify(() -> {
                assertEquals(HttpVersion.HTTP_2, request.version());
                assertEquals(HttpMethod.GET, request.method());
                assertEquals("/", request.path());
                assertEquals("http", request.scheme());
                assertEquals(host, request.authority(true).host());
                assertEquals(request.localAddress().port(), request.authority(true).port());
                assertEquals("retained", request.getHeader("application"));
                assertRequestState(request.headers(), state, allowed, codec);
                new HttpContextCarrier(request.response().headers()).set("flow_id", remoteRemoval
                    ? codec.marshalRemovalToString()
                    : codec.marshalToString(FlowId.builder().withId("server-response").build()));
                request.response().end("delivered");
            })).listen(0, "127.0.0.1").compose(server -> {
                int port = server.actualPort();
                HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + port),
                    URI.create("http://trusted.invalid:" + port));
                return inFlow(vertx, state, ignored -> {
                    ContextInternal caller = ContextInternal.current();
                    FlowId original = initialize(state);
                    var request = client.get(port, "127.0.0.1", "/");
                    if (!allowed) {
                        request.virtualHost(host);
                    }
                    request.putHeader(HttpContextCarrier.name("flow_id"), "stale-managed-header")
                        .putHeader("application", "retained");
                    return request.timeout(3_000).send().map(response -> {
                        assertEquals(HttpVersion.HTTP_2, response.version());
                        assertEquals("delivered", response.bodyAsString());
                        assertTrue(HttpContextCarrier.hasMetadata(response.headers()));
                        assertSame(caller, ContextInternal.current());
                        if (state.equals("empty") && allowed && !remoteRemoval) {
                            assertEquals("server-response", ((FlowId) Storage.get("flow_id")).getId());
                            assertFalse(DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                        } else if (state.equals("empty") && allowed && remoteRemoval) {
                            assertState("removed", null);
                        } else {
                            assertState(state, original);
                        }
                        return response;
                    });
                });
            }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @MethodSource("callerModes")
    void client_pseudoHeaderInjection_rejectsBeforeSendingAndLeavesNextRequestUsable(
            boolean multiplex, String state, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient client = client(vertx, multiplex);
        AtomicInteger prepared = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();
        FlowIdService codec = new FlowIdService();
        vertx.createHttpServer(new HttpServerOptions().setHttp2MultiplexImplementation(multiplex))
            .requestHandler(request -> context.verify(() -> {
                delivered.incrementAndGet();
                assertEquals(HttpVersion.HTTP_2, request.version());
                assertEquals(HttpMethod.GET, request.method());
                assertEquals("/valid", request.path());
                assertEquals("http", request.scheme());
                assertEquals("127.0.0.1", request.authority(true).host());
                assertEquals("retained", request.getHeader("application"));
                assertRequestState(request.headers(), state, true, codec);
                request.response().end("valid");
            })).listen(0, "127.0.0.1").compose(server -> {
                HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + server.actualPort()));
                client.addInterceptor(event -> {
                    if (event.phase() == ClientPhase.PREPARE_REQUEST) {
                        prepared.incrementAndGet();
                    }
                    event.next();
                });
                return inFlow(vertx, state, ignored -> {
                    ContextInternal caller = ContextInternal.current();
                    FlowId original = initialize(state);
                    var request = client.get(server.actualPort(), "127.0.0.1", "/valid")
                        .putHeader("application", "retained");
                    for (String name : List.of(":authority", ":scheme", ":path", ":method")) {
                        assertThrows(IllegalArgumentException.class, () -> request.putHeader(name, "override"), name);
                        assertNull(request.headers().get(name));
                        assertState(state, original);
                    }
                    assertEquals(0, prepared.get());
                    assertEquals(0, delivered.get());
                    return request.timeout(3_000).send().map(response -> {
                        assertEquals("valid", response.bodyAsString());
                        assertEquals(HttpVersion.HTTP_2, response.version());
                        assertEquals(1, prepared.get());
                        assertEquals(1, delivered.get());
                        assertSame(caller, ContextInternal.current());
                        assertState(state, original);
                        return response;
                    });
                });
            }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    private static WebClient client(Vertx vertx, boolean multiplex) {
        WebClientOptions options = new WebClientOptions().setProtocolVersion(HttpVersion.HTTP_2)
            .setHttp2ClearTextUpgrade(false);
        options.setHttp2MultiplexImplementation(multiplex);
        return WebClient.create(vertx, options);
    }

    private static FlowId initialize(String state) {
        FlowId original = FlowId.builder().withId("caller").build();
        if (state.equals("value")) {
            Storage.put("flow_id", original);
        } else if (state.equals("removed")) {
            Storage.remove("flow_id");
        }
        return original;
    }

    private static void assertState(String state, FlowId original) {
        if (state.equals("value")) {
            assertSame(original, Storage.get("flow_id"));
        } else {
            assertNull(Storage.get("flow_id"));
        }
        assertEquals(state.equals("removed"), DefaultStorage.isRemoved(DefaultStorage.getEntry(ContextInternal.current(), "flow_id")));
    }

    private static void assertRequestState(MultiMap headers, String state, boolean allowed, FlowIdService codec) {
        if (!allowed || state.equals("empty")) {
            assertFalse(HttpContextCarrier.hasMetadata(headers));
        } else if (state.equals("removed")) {
            assertTrue(codec.isRemoval(new HttpContextCarrier(headers).getAll("flow_id").getFirst()));
        } else {
            String encoded = new HttpContextCarrier(headers).getAll("flow_id").getFirst();
            assertEquals("caller", ((FlowId) codec.unmarshalFormString(encoded)).getId());
        }
    }

    private static Stream<Arguments> callerModes() {
        return Stream.of(false, true).flatMap(multiplex -> Stream.of("empty", "value", "removed")
            .map(state -> Arguments.of(multiplex, state)));
    }

    private static Stream<Arguments> routingModes() {
        return Stream.of(false, true).flatMap(multiplex -> Stream.of(Routing.values())
            .flatMap(routing -> Stream.of("empty", "value", "removed").flatMap(state -> Stream.of(false, true)
                .map(removal -> Arguments.of(multiplex, routing, state, removal)))));
    }
}
