package com.inqwise.context;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.internal.http.HttpServerRequestInternal;
import io.vertx.core.internal.http.HttpServerRequestWrapper;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class HttpInstallationGuardTest {
    private record Incoming(HttpServerRequest request, ContextInternal scope) {
    }

    @ParameterizedTest
    @MethodSource("requestModes")
    void handler_invalidDispatch_rejectsBeforeApplicationAndAllowsOriginalRequestScope(
            HttpVersion version, boolean multiplex, boolean wrapped, Vertx vertx, VertxTestContext testContext) throws Exception {
        vertx.exceptionHandler(testContext::failNow);
        WebClientOptions options = new WebClientOptions().setProtocolVersion(version).setHttp2ClearTextUpgrade(false);
        options.setHttp2MultiplexImplementation(multiplex);
        WebClient client = WebClient.create(vertx, options);
        CompletableFuture<Incoming> captured = new CompletableFuture<>();
        CompletableFuture<ContextInternal> applicationScope = new CompletableFuture<>();
        AtomicInteger invoked = new AtomicInteger();
        FlowId original = FlowId.builder().withId("root-only").build();
        FlowId outgoing = FlowId.builder().withId("valid-request").build();
        var handler = HttpContextTransport.handler(request -> {
            invoked.incrementAndGet();
            applicationScope.complete(ContextInternal.current());
            Storage.put("flow_id", outgoing);
            request.response().end("delivered");
        });
        var server = vertx.createHttpServer(new HttpServerOptions().setHttp2MultiplexImplementation(multiplex)).requestHandler(request -> {
            HttpServerRequest incoming = wrapped ? new HttpServerRequestWrapper((HttpServerRequestInternal) request) : request;
            captured.complete(new Incoming(incoming, ContextInternal.current()));
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var pending = client.get(server.actualPort(), "127.0.0.1", "/").send();
        Incoming incoming = captured.get(5, TimeUnit.SECONDS);
        assertTrue(incoming.scope().isDuplicate());
        assertNull(Vertx.currentContext());
        assertThrows(IllegalStateException.class, () -> handler.handle(incoming.request()));
        assertEquals(0, invoked.get());
        assertFalse(incoming.request().response().headWritten());
        ContextInternal root = (ContextInternal) vertx.getOrCreateContext();
        ContextInternal unrelated = root.duplicate();
        root.runOnContext(ignored -> testContext.verify(() -> {
            assertFalse(root.isDuplicate());
            Storage.put("flow_id", original);
            assertThrows(IllegalStateException.class, () -> handler.handle(incoming.request()));
            assertEquals(0, invoked.get());
            assertSame(original, Storage.get("flow_id"));
            assertFalse(incoming.request().response().headWritten());
            unrelated.runOnContext(tick -> testContext.verify(() -> {
                assertTrue(unrelated.isDuplicate());
                assertNotSame(incoming.scope(), unrelated);
                FlowId unrelatedValue = FlowId.builder().withId("unrelated-context").build();
                Storage.put("flow_id", unrelatedValue);
                assertThrows(IllegalStateException.class, () -> handler.handle(incoming.request()));
                assertEquals(0, invoked.get());
                assertSame(unrelatedValue, Storage.get("flow_id"));
                assertFalse(incoming.request().response().headWritten());
                incoming.scope().runOnContext(done -> handler.handle(incoming.request()));
            }));
        }));
        pending.andThen(ignored -> client.close()).onComplete(testContext.succeeding(response -> testContext.verify(() -> {
            assertEquals("delivered", response.bodyAsString());
            assertEquals(version, response.version());
            assertEquals(1, invoked.get());
            assertSame(incoming.scope(), applicationScope.getNow(null));
            assertEquals("no-store", response.getHeader("Cache-Control"));
            assertEquals(outgoing.toJson(), ((FlowId) new FlowIdService().unmarshalFormString(
                new HttpContextCarrier(response.headers()).getAll("flow_id").getFirst())).toJson());
            testContext.completeNow();
        })));
    }

    @ParameterizedTest
    @MethodSource("invalidKeys")
    void install_invalidConfiguration_rejectsWithoutPartiallyInstrumentingClient(
            String key, Vertx vertx, VertxTestContext testContext) {
        vertx.exceptionHandler(testContext::failNow);
        WebClient client = WebClient.create(vertx);
        var invalid = ContextTransportConfiguration.builder().include("flow_id").include(key).build();
        AtomicInteger invoked = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> HttpContextTransport.install(client, invalid));
        assertThrows(IllegalArgumentException.class, () -> HttpContextTransport.handler(invalid, request -> invoked.incrementAndGet()));
        FlowId original = FlowId.builder().withId("caller").build();
        vertx.createHttpServer().requestHandler(HttpContextTransport.handler(request -> testContext.verify(() -> {
            invoked.incrementAndGet();
            assertEquals("caller", ((FlowId) Storage.get("flow_id")).getId());
            assertEquals(1, new HttpContextCarrier(request.headers()).getAll("flow_id").size());
            request.response().end("delivered");
        }))).listen(0, "127.0.0.1").compose(server -> {
            assertSame(client, HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + server.actualPort())));
            return inFlow(vertx, "valid-installation", ignored -> {
                Storage.put("flow_id", original);
                return client.get(server.actualPort(), "127.0.0.1", "/").send().map(response -> {
                    assertEquals("delivered", response.bodyAsString());
                    assertEquals(1, invoked.get());
                    assertSame(original, Storage.get("flow_id"));
                    return response;
                });
            });
        }).andThen(ignored -> client.close()).onComplete(testContext.succeedingThenComplete());
    }

    @Test
    void send_withoutCallingContext_failsBeforeNetworkAndAllowsLaterDeployedUse(
            Vertx vertx, VertxTestContext testContext) throws Exception {
        vertx.exceptionHandler(testContext::failNow);
        WebClient client = WebClient.create(vertx);
        AtomicInteger received = new AtomicInteger();
        var server = vertx.createHttpServer().requestHandler(HttpContextTransport.handler(request -> testContext.verify(() -> {
            received.incrementAndGet();
            assertEquals("deployed", ((FlowId) Storage.get("flow_id")).getId());
            request.response().end("delivered");
        }))).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + server.actualPort()));
        assertNull(Vertx.currentContext());
        var failed = client.get(server.actualPort(), "127.0.0.1", "/invalid").send();
        assertTrue(failed.failed());
        assertInstanceOf(IllegalStateException.class, failed.cause());
        assertEquals(0, received.get());
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                FlowId original = FlowId.builder().withId("deployed").build();
                Storage.put("flow_id", original);
                return client.get(server.actualPort(), "127.0.0.1", "/valid").send().map(response -> {
                    assertEquals("delivered", response.bodyAsString());
                    assertEquals(1, received.get());
                    assertSame(original, Storage.get("flow_id"));
                    return response;
                });
            }
        }).andThen(ignored -> client.close()).onComplete(testContext.succeedingThenComplete());
    }

    private static Stream<String> invalidKeys() {
        return Stream.of("FLOW_ID", "space key", "line\nbreak", "colon:key", "x".repeat(129));
    }

    private static Stream<Arguments> requestModes() {
        return Stream.of(HttpVersion.HTTP_1_1, HttpVersion.HTTP_2)
            .flatMap(version -> (version == HttpVersion.HTTP_1_1 ? Stream.of(false) : Stream.of(false, true))
                .flatMap(multiplex -> Stream.of(false, true).map(wrapped -> Arguments.of(version, multiplex, wrapped))));
    }
}
