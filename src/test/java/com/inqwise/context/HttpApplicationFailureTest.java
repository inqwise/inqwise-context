package com.inqwise.context;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.internal.ContextInternal;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
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
class HttpApplicationFailureTest {
    @ParameterizedTest
    @MethodSource("failureModes")
    void handler_applicationFailure_retainsNativeIdentityAndRequestContext(
            ThreadingModel model, boolean assertion, boolean removal, boolean instrumented,
            Vertx vertx, VertxTestContext testContext) {
        WebClient client = WebClient.create(vertx);
        Throwable expected = assertion ? new AssertionError("Controlled HTTP application error")
            : new IllegalStateException("Controlled HTTP application failure");
        AtomicReference<HttpServerRequest> failedRequest = new AtomicReference<>();
        AtomicReference<ContextInternal> failedScope = new AtomicReference<>();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        FlowId original = FlowId.builder().withId("caller").build();
        FlowId beforeFailure = FlowId.builder().withId("before-failure").build();
        FlowId recovery = FlowId.builder().withId("error-observer").build();
        vertx.exceptionHandler(error -> testContext.verify(() -> {
            assertSame(expected, error);
            assertSame(failedScope.get(), ContextInternal.current());
            assertSame(beforeFailure, Storage.get("flow_id"));
            assertSame(beforeFailure, Storage.get("server-private"));
            failures.incrementAndGet();
            if (removal) {
                Storage.remove("flow_id");
            } else {
                Storage.put("flow_id", recovery);
            }
            failedRequest.get().response().setStatusCode(503).end("handled");
        }));
        Handler<HttpServerRequest> application = request -> {
            requests.incrementAndGet();
            assertNull(Storage.get("server-private"));
            if (instrumented) {
                assertEquals(original.toJson(), ((FlowId) Storage.get("flow_id")).toJson());
                assertNotSame(original, Storage.get("flow_id"));
            } else {
                assertNull(Storage.get("flow_id"));
            }
            if (request.path().equals("/healthy")) {
                assertNotSame(failedScope.get(), ContextInternal.current());
                Storage.put("flow_id", FlowId.builder().withId("healthy").build());
                request.response().end("healthy");
                return;
            }
            failedRequest.set(request);
            failedScope.set(ContextInternal.current());
            Storage.put("flow_id", beforeFailure);
            Storage.put("server-private", beforeFailure);
            if (assertion) {
                throw (AssertionError) expected;
            }
            throw (RuntimeException) expected;
        };
        vertx.createHttpServer().requestHandler(instrumented ? HttpContextTransport.handler(application) : application)
            .listen(0, "127.0.0.1").compose(server -> {
                if (instrumented) {
                    HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + server.actualPort()));
                }
                return vertx.deployVerticle(new VerticleBase() {
                    @Override
                    public Future<?> start() {
                        ContextInternal caller = ContextInternal.current();
                        Storage.put("flow_id", original);
                        return client.get(server.actualPort(), "127.0.0.1", "/failed").send().compose(response -> {
                            assertSame(caller, ContextInternal.current());
                            assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                            assertSame(original, Storage.get("flow_id"));
                            assertNull(Storage.get("server-private"));
                            assertEquals(503, response.statusCode());
                            assertEquals("handled", response.bodyAsString());
                            assertEquals(1, failures.get());
                            if (instrumented) {
                                assertEquals("no-store", response.getHeader("Cache-Control"));
                                String encoded = new HttpContextCarrier(response.headers()).getAll("flow_id").getFirst();
                                FlowIdService codec = new FlowIdService();
                                if (removal) {
                                    assertTrue(codec.isRemoval(encoded));
                                } else {
                                    assertEquals(recovery.toJson(), ((FlowId) codec.unmarshalFormString(encoded)).toJson());
                                }
                            } else {
                                assertFalse(HttpContextCarrier.hasMetadata(response.headers()));
                            }
                            return client.get(server.actualPort(), "127.0.0.1", "/healthy").send();
                        }).compose(response -> {
                            assertEquals("healthy", response.bodyAsString());
                            assertEquals(200, response.statusCode());
                            assertEquals(2, requests.get());
                            assertEquals(1, failures.get());
                            assertSame(caller, ContextInternal.current());
                            assertSame(original, Storage.get("flow_id"));
                            return vertx.timer(1);
                        }).map(tick -> {
                            assertSame(caller, ContextInternal.current());
                            assertSame(original, Storage.get("flow_id"));
                            assertNull(Storage.get("server-private"));
                            return tick;
                        });
                    }
                }, new DeploymentOptions().setThreadingModel(model));
            }).andThen(ignored -> client.close()).onComplete(testContext.succeedingThenComplete());
    }

    private static Stream<Arguments> failureModes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of(false, true).flatMap(assertion -> Stream.of(false, true)
                .flatMap(removal -> Stream.of(false, true)
                    .map(instrumented -> Arguments.of(model, assertion, removal, instrumented)))));
    }
}
