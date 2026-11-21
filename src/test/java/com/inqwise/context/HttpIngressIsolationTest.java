package com.inqwise.context;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.PoolOptions;
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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class HttpIngressIsolationTest {
    @ParameterizedTest
    @MethodSource("serverModes")
    void serverStartupAndReusedConnection_doNotLeakStorageBetweenRequests(ThreadingModel model,
            boolean duplicateOwner, boolean pipelining, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient client = WebClient.create(vertx, new WebClientOptions().setProtocolVersion(HttpVersion.HTTP_1_1)
            .setKeepAlive(true).setPipelining(pipelining).setPipeliningLimit(3), new PoolOptions().setHttp1MaxSize(1));
        Promise<HttpServer> ready = Promise.promise();
        AtomicReference<ContextInternal> owner = new AtomicReference<>();
        AtomicReference<HttpConnection> connection = new AtomicReference<>();
        List<ContextInternal> requestScopes = new CopyOnWriteArrayList<>();
        FlowId startup = FlowId.builder().withId("server-startup").build();
        FlowId original = FlowId.builder().withId("client").build();
        VerticleBase serverVerticle = new VerticleBase() {
            @Override
            public Future<?> start() {
                ContextInternal startupScope = duplicateOwner ? ContextInternal.current().duplicate() : ContextInternal.current();
                startupScope.runOnContext(ignored -> {
                    try {
                        owner.set(startupScope);
                        Storage.put("flow_id", startup);
                        Storage.put("startup-private", startup);
                        vertx.createHttpServer().requestHandler(HttpContextTransport.handler(request -> {
                            try {
                                ContextInternal scope = ContextInternal.current();
                                assertNotSame(startupScope, scope);
                                assertEquals(model, scope.threadingModel());
                                assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                                assertTrue(scope.isDuplicate());
                                requestScopes.add(scope);
                                connection.compareAndSet(null, request.connection());
                                assertSame(connection.get(), request.connection());
                                assertNull(Storage.get("startup-private"));
                                String state = request.path().substring(1);
                                assertRequestState(state);
                                FlowId incoming = (FlowId) Storage.get("flow_id");
                                if (state.equals("value")) {
                                    assertNotSame(original, incoming);
                                }
                                request.body().compose(body -> {
                                    assertSame(scope, ContextInternal.current());
                                    assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                                    assertEquals(state, body.toString());
                                    assertSame(incoming, Storage.get("flow_id"));
                                    assertRequestState(state);
                                    return vertx.timer(1);
                                }).onComplete(tick -> {
                                    try {
                                        assertTrue(tick.succeeded(), () -> String.valueOf(tick.cause()));
                                        assertSame(scope, ContextInternal.current());
                                        assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                                        assertSame(incoming, Storage.get("flow_id"));
                                        assertRequestState(state);
                                        if (state.equals("value")) {
                                            Storage.remove("flow_id");
                                        } else {
                                            Storage.put("flow_id", FlowId.builder().withId("response-" + state).build());
                                        }
                                        Storage.put("startup-private", incoming == null ? original : incoming);
                                        request.response().end(state);
                                    } catch (Throwable failure) {
                                        fail(request, failure);
                                    }
                                });
                            } catch (Throwable failure) {
                                fail(request, failure);
                            }
                        })).listen(0, "127.0.0.1").onComplete(ready);
                    } catch (Throwable failure) {
                        ready.tryFail(failure);
                    }
                });
                return ready.future();
            }
        };
        vertx.deployVerticle(serverVerticle, new DeploymentOptions().setThreadingModel(model))
            .compose(ignored -> ready.future()).compose(server -> {
                HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + server.actualPort()));
                if (pipelining) {
                    List<Future<Void>> calls = new ArrayList<>();
                    for (String state : List.of("value", "removed", "empty")) {
                        calls.add(send(vertx, client, server.actualPort(), state, original));
                    }
                    return Future.join(calls).mapEmpty();
                }
                Future<Void> calls = Future.succeededFuture();
                for (String state : List.of("value", "removed", "empty")) {
                    calls = calls.compose(ignored -> send(vertx, client, server.actualPort(), state, original));
                }
                return calls;
            }).map(ignored -> {
                assertEquals(3, requestScopes.size());
                assertEquals(3, requestScopes.stream().distinct().count());
                assertSame(startup, DefaultStorage.get(owner.get(), "flow_id"));
                assertSame(startup, DefaultStorage.get(owner.get(), "startup-private"));
                return ignored;
            }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    private static Future<Void> send(Vertx vertx, WebClient client, int port, String state, FlowId original) {
        return inFlow(vertx, state, ignored -> {
            ContextInternal caller = ContextInternal.current();
            if (state.equals("value")) {
                Storage.put("flow_id", original);
            } else if (state.equals("removed")) {
                Storage.remove("flow_id");
            }
            return client.post(port, "127.0.0.1", "/" + state).timeout(3_000).sendBuffer(Buffer.buffer(state)).map(response -> {
                assertEquals(200, response.statusCode(), response.bodyAsString());
                assertEquals(HttpVersion.HTTP_1_1, response.version());
                assertSame(caller, ContextInternal.current());
                assertEquals(state, response.bodyAsString());
                assertNull(Storage.get("startup-private"));
                if (state.equals("value")) {
                    assertSame(original, Storage.get("flow_id"));
                    assertTrue(new FlowIdService().isRemoval(new HttpContextCarrier(response.headers()).getAll("flow_id").getFirst()));
                } else if (state.equals("removed")) {
                    assertRequestState(state);
                    assertEquals("response-removed", ((FlowId) new FlowIdService().unmarshalFormString(
                        new HttpContextCarrier(response.headers()).getAll("flow_id").getFirst())).getId());
                } else {
                    assertEquals("response-empty", ((FlowId) Storage.get("flow_id")).getId());
                }
                return response;
            });
        });
    }

    private static void assertRequestState(String state) {
        if (state.equals("value")) {
            assertEquals("client", ((FlowId) Storage.get("flow_id")).getId());
        } else {
            assertNull(Storage.get("flow_id"));
            MultiMap headers = MultiMap.caseInsensitiveMultiMap();
            ContextTransport.writeTo(new HttpContextCarrier(headers));
            List<String> values = new HttpContextCarrier(headers).getAll("flow_id");
            if (state.equals("removed")) {
                assertEquals(1, values.size());
                assertTrue(new FlowIdService().isRemoval(values.getFirst()));
            } else {
                assertTrue(values.isEmpty());
            }
        }
    }

    private static void fail(HttpServerRequest request, Throwable failure) {
        request.response().setStatusCode(500).end(failure.toString());
    }

    private static Stream<Arguments> serverModes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of(false, true)
                .flatMap(duplicate -> Stream.of(false, true).map(pipelining -> Arguments.of(model, duplicate, pipelining))));
    }
}
