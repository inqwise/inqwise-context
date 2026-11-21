package com.inqwise.context;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.impl.ClientPhase;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class HttpInterceptorFailureTest {
    @ParameterizedTest
    @MethodSource("failureModes")
    void interceptor_runtimeFailure_preservesNativeFutureAndHeaderImportBoundary(
            ThreadingModel model, ClientPhase phase, boolean instrumented,
            Vertx vertx, VertxTestContext testContext) {
        vertx.exceptionHandler(testContext::failNow);
        WebClient client = WebClient.create(vertx);
        IllegalStateException expected = new IllegalStateException("Controlled application interceptor failure");
        AtomicInteger received = new AtomicInteger();
        boolean headersReceived = phase == ClientPhase.RECEIVE_RESPONSE || phase == ClientPhase.DISPATCH_RESPONSE;
        FlowId privateItem = FlowId.builder().withId("caller-private").build();
        vertx.createHttpServer().requestHandler(HttpContextTransport.handler(request -> testContext.verify(() -> {
            received.incrementAndGet();
            assertNull(Storage.get("interceptor-private"));
            if (instrumented && headersReceived && request.path().equals("/healthy")) {
                assertEquals("/failed", ((FlowId) Storage.get("flow_id")).getId());
            } else {
                assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
            }
            Storage.put("flow_id", FlowId.builder().withId(request.path()).build());
            request.response().end(request.path());
        }))).listen(0, "127.0.0.1").compose(server -> {
            if (instrumented) {
                HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + server.actualPort()));
            }
            // This application interceptor observes phases without changing routing or headers.
            client.addInterceptor(event -> {
                if (event.request().uri().equals("/failed") && event.phase() == phase) {
                    throw expected;
                }
                event.next();
            });
            return vertx.deployVerticle(new VerticleBase() {
                @Override
                public Future<?> start() {
                    ContextInternal caller = ContextInternal.current();
                    Storage.put("interceptor-private", privateItem);
                    return client.get(server.actualPort(), "127.0.0.1", "/failed").send().transform(result -> {
                        assertTrue(result.failed());
                        assertSame(expected, result.cause());
                        assertSame(caller, ContextInternal.current());
                        assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                        assertSame(privateItem, Storage.get("interceptor-private"));
                        assertEquals(headersReceived ? 1 : 0, received.get());
                        if (instrumented && headersReceived) {
                            assertEquals("/failed", ((FlowId) Storage.get("flow_id")).getId());
                        } else {
                            assertNull(Storage.get("flow_id"));
                        }
                        var current = Storage.get("flow_id");
                        return vertx.timer(1).map(tick -> {
                            assertSame(caller, ContextInternal.current());
                            assertSame(current, Storage.get("flow_id"));
                            return current;
                        });
                    }).compose(current -> client.get(server.actualPort(), "127.0.0.1", "/healthy").send().map(response -> {
                        assertEquals("/healthy", response.bodyAsString());
                        assertEquals(headersReceived ? 2 : 1, received.get());
                        assertSame(caller, ContextInternal.current());
                        assertSame(privateItem, Storage.get("interceptor-private"));
                        if (current != null) {
                            assertSame(current, Storage.get("flow_id"));
                        } else if (instrumented) {
                            assertEquals("/healthy", ((FlowId) Storage.get("flow_id")).getId());
                        } else {
                            assertNull(Storage.get("flow_id"));
                        }
                        return response;
                    }));
                }
            }, new DeploymentOptions().setThreadingModel(model));
        }).andThen(ignored -> client.close()).onComplete(testContext.succeedingThenComplete());
    }

    private static Stream<Arguments> failureModes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of(ClientPhase.PREPARE_REQUEST, ClientPhase.SEND_REQUEST,
                ClientPhase.RECEIVE_RESPONSE, ClientPhase.DISPATCH_RESPONSE)
                .flatMap(phase -> Stream.of(false, true).map(instrumented -> Arguments.of(model, phase, instrumented))));
    }
}
