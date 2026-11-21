package com.inqwise.context;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.spi.ContextChainDataItem;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.internal.ContextInternal;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.WebClientSession;
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
class HttpSessionContextTransportTest {
    @ParameterizedTest
    @MethodSource("sessionModes")
    void session_redirect_sanitizesEachHopAndImportsOnlyFinalMetadata(
            HttpVersion version, boolean multiplex, boolean allowed, String state, boolean remoteRemoval,
            Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient base = client(vertx, version, multiplex);
        WebClientSession session = WebClientSession.create(base);
        FlowIdService codec = new FlowIdService();
        FlowId original = FlowId.builder().withId("caller").build();
        AtomicInteger sourceRequests = new AtomicInteger();
        AtomicInteger targetRequests = new AtomicInteger();
        vertx.createHttpServer(new HttpServerOptions().setHttp2MultiplexImplementation(multiplex))
            .requestHandler(request -> context.verify(() -> {
                targetRequests.incrementAndGet();
                assertEquals(version, request.version());
                assertRedirectRequest(request, state, allowed, original);
                assertTrue(request.getHeader("Cookie").contains("redirect=retained"));
                new HttpContextCarrier(request.response().headers()).set("flow_id", remoteRemoval
                    ? codec.marshalRemovalToString()
                    : codec.marshalToString(FlowId.builder().withId("server-response").build()));
                request.response().end("delivered");
            })).listen(0, "127.0.0.1").compose(target -> vertx.createHttpServer(
                new HttpServerOptions().setHttp2MultiplexImplementation(multiplex))
                .requestHandler(request -> context.verify(() -> {
                    sourceRequests.incrementAndGet();
                    assertEquals(version, request.version());
                    assertRedirectRequest(request, state, true, original);
                    // Followed responses may update cookies, but must not decode metadata.
                    request.response().putHeader(HttpContextCarrier.name("flow_id"), "1:%%%");
                    request.response().putHeader("Set-Cookie", "redirect=retained; Path=/")
                        .setStatusCode(302).putHeader("Location", origin(target.actualPort()) + "/target").end();
                })).listen(0, "127.0.0.1").compose(source -> {
                    HttpContextTransport.install(session, allowed
                        ? new URI[] {origin(source.actualPort()), origin(target.actualPort())}
                        : new URI[] {origin(source.actualPort())});
                    defaults(session);
                    return inFlow(vertx, state, ignored -> {
                        ContextInternal caller = ContextInternal.current();
                        initialize(state, original);
                        return session.get(source.actualPort(), "127.0.0.1", "/source").send().map(response -> {
                            assertEquals("delivered", response.bodyAsString());
                            assertEquals(version, response.version());
                            assertEquals(1, response.followedRedirects().size());
                            assertEquals(1, sourceRequests.get());
                            assertEquals(1, targetRequests.get());
                            assertSame(caller, ContextInternal.current());
                            assertState(state, original, allowed, remoteRemoval);
                            return response;
                        });
                    });
                })).andThen(ignored -> base.close()).onComplete(context.succeedingThenComplete());
    }

    private static void assertRedirectRequest(HttpServerRequest request, String state, boolean allowed, FlowId original) {
        assertEquals("retained", request.getHeader("application"));
        assertNull(request.getHeader(HttpContextCarrier.name("private")));
        if (!allowed || state.equals("empty")) {
            assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
        } else {
            var values = new HttpContextCarrier(request.headers()).getAll("flow_id");
            assertEquals(1, values.size());
            FlowIdService codec = new FlowIdService();
            if (state.equals("removed")) {
                assertTrue(codec.isRemoval(values.getFirst()));
            } else {
                assertEquals(original.toJson(), ((FlowId) codec.unmarshalFormString(values.getFirst())).toJson());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("sessionModes")
    void session_createdBeforeInstallation_sanitizesDefaultsAndPreservesCookies(
            HttpVersion version, boolean multiplex, boolean allowed, String state, boolean remoteRemoval,
            Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient base = client(vertx, version, multiplex);
        WebClientSession session = WebClientSession.create(base);
        FlowIdService codec = new FlowIdService();
        FlowId original = FlowId.builder().withId("caller").build();
        AtomicInteger received = new AtomicInteger();
        vertx.createHttpServer(new HttpServerOptions().setHttp2MultiplexImplementation(multiplex))
            .requestHandler(request -> context.verify(() -> {
                received.incrementAndGet();
                assertEquals(version, request.version());
                assertEquals("retained", request.getHeader("application"));
                assertNull(request.getHeader(HttpContextCarrier.name("private")));
                if (request.path().equals("/second")) {
                    assertTrue(request.getHeader("Cookie").contains("session=retained"));
                }
                boolean empty = state.equals("empty") && request.path().equals("/first");
                if (!allowed || empty) {
                    assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
                } else {
                    String encoded = new HttpContextCarrier(request.headers()).getAll("flow_id").getFirst();
                    boolean removed = state.equals("removed") || (state.equals("empty") && remoteRemoval);
                    if (removed) {
                        assertTrue(codec.isRemoval(encoded));
                    } else {
                        assertEquals(state.equals("value") ? "caller" : "server-response",
                            ((FlowId) codec.unmarshalFormString(encoded)).getId());
                    }
                }
                new HttpContextCarrier(request.response().headers()).set("flow_id", remoteRemoval
                    ? codec.marshalRemovalToString()
                    : codec.marshalToString(FlowId.builder().withId("server-response").build()));
                request.response().putHeader("Set-Cookie", "session=retained; Path=/").end("delivered");
            })).listen(0, "127.0.0.1").compose(server -> {
                HttpContextTransport.install(session, allowed ? new URI[] {origin(server.actualPort())} : new URI[0]);
                defaults(session);
                return inFlow(vertx, state, ignored -> {
                    ContextInternal caller = ContextInternal.current();
                    initialize(state, original);
                    return session.get(server.actualPort(), "127.0.0.1", "/first").send().compose(first -> {
                        assertEquals("delivered", first.bodyAsString());
                        assertSame(caller, ContextInternal.current());
                        assertState(state, original, allowed, remoteRemoval);
                        ContextChainDataItem before = Storage.get("flow_id");
                        boolean removed = DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id"));
                        return session.get(server.actualPort(), "127.0.0.1", "/second").send().map(second -> {
                            assertEquals("delivered", second.bodyAsString());
                            assertEquals(version, second.version());
                            assertSame(caller, ContextInternal.current());
                            assertSame(before, Storage.get("flow_id"));
                            assertEquals(removed, DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                            assertEquals(2, received.get());
                            return second;
                        });
                    });
                });
            }).andThen(ignored -> base.close()).onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @MethodSource("inheritedModes")
    void session_createdAfterInstallation_failsBeforeSendingAndLeavesParentUsable(
            HttpVersion version, boolean multiplex, boolean allowed, String state,
            Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient base = client(vertx, version, multiplex);
        FlowId original = FlowId.builder().withId("caller").build();
        AtomicInteger received = new AtomicInteger();
        vertx.createHttpServer(new HttpServerOptions().setHttp2MultiplexImplementation(multiplex))
            .requestHandler(request -> {
                received.incrementAndGet();
                request.response().end("parent");
            }).listen(0, "127.0.0.1").compose(server -> {
                HttpContextTransport.install(base, allowed ? new URI[] {origin(server.actualPort())} : new URI[0]);
                WebClientSession session = WebClientSession.create(base);
                defaults(session);
                return inFlow(vertx, state, ignored -> {
                    ContextInternal caller = ContextInternal.current();
                    initialize(state, original);
                    return session.get(server.actualPort(), "127.0.0.1", "/unsafe").send().transform(result -> {
                        assertTrue(result.failed());
                        IllegalStateException failure = assertInstanceOf(IllegalStateException.class, result.cause());
                        assertTrue(failure.getMessage().contains("session"));
                        assertEquals(0, received.get());
                        assertSame(caller, ContextInternal.current());
                        assertState(state, original, false, false);
                        return base.get(server.actualPort(), "127.0.0.1", "/parent").send().map(response -> {
                            assertEquals("parent", response.bodyAsString());
                            assertEquals(1, received.get());
                            assertSame(caller, ContextInternal.current());
                            assertState(state, original, false, false);
                            return response;
                        });
                    });
                });
            }).andThen(ignored -> base.close()).onComplete(context.succeedingThenComplete());
    }

    private static void defaults(WebClientSession session) {
        session.addHeader("application", "retained");
        session.addHeader(HttpContextCarrier.name("flow_id"), "stale-managed-header");
        session.addHeader(HttpContextCarrier.name("private"), "stale-private-header");
    }

    private static WebClient client(Vertx vertx, HttpVersion version, boolean multiplex) {
        WebClientOptions options = new WebClientOptions().setProtocolVersion(version).setHttp2ClearTextUpgrade(false);
        options.setHttp2MultiplexImplementation(multiplex);
        return WebClient.create(vertx, options);
    }

    private static URI origin(int port) {
        return URI.create("http://127.0.0.1:" + port);
    }

    private static void initialize(String state, FlowId original) {
        if (state.equals("value")) {
            Storage.put("flow_id", original);
        } else if (state.equals("removed")) {
            Storage.remove("flow_id");
        }
    }

    private static void assertState(String state, FlowId original, boolean allowed, boolean remoteRemoval) {
        ContextInternal caller = ContextInternal.current();
        if (state.equals("value")) {
            assertSame(original, Storage.get("flow_id"));
        } else if (state.equals("empty") && allowed && !remoteRemoval) {
            assertEquals("server-response", ((FlowId) Storage.get("flow_id")).getId());
        } else {
            assertNull(Storage.get("flow_id"));
        }
        assertEquals(state.equals("removed") || (state.equals("empty") && allowed && remoteRemoval),
            DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
    }

    private static Stream<Arguments> inheritedModes() {
        return Stream.of(HttpVersion.HTTP_1_1, HttpVersion.HTTP_2)
            .flatMap(version -> (version == HttpVersion.HTTP_1_1 ? Stream.of(false) : Stream.of(false, true))
                .flatMap(multiplex -> Stream.of(false, true).flatMap(allowed -> Stream.of("empty", "value", "removed")
                    .map(state -> Arguments.of(version, multiplex, allowed, state)))));
    }

    private static Stream<Arguments> sessionModes() {
        return inheritedModes().flatMap(mode -> Stream.of(false, true).map(removal -> Arguments.of(
            mode.get()[0], mode.get()[1], mode.get()[2], mode.get()[3], removal)));
    }
}
