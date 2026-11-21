package com.inqwise.context;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.StreamResetException;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.streams.WriteStream;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.impl.ClientPhase;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class HttpCancellationContextTest {
    @ParameterizedTest
    @MethodSource("cancellationModes")
    void response_nativeCancellation_preservesChangedStateAndOtherActiveRequest(
            ThreadingModel model, HttpVersion version, boolean multiplex, String localState, boolean removal, boolean headersFirst,
            Vertx vertx, VertxTestContext testContext) {
        vertx.exceptionHandler(testContext::failNow);
        WebClientOptions options = new WebClientOptions().setProtocolVersion(version).setHttp2ClearTextUpgrade(false);
        options.setHttp2MultiplexImplementation(multiplex);
        options.setPipelining(false);
        WebClient client = WebClient.create(vertx, options,
            new PoolOptions().setHttp1MaxSize(2).setHttp2MaxSize(1).setEventLoopSize(1));
        Promise<HttpClientRequest> nativeRequest = Promise.promise();
        Promise<Throwable> nativeFailure = Promise.promise();
        Promise<Void> bothArrived = Promise.promise();
        Promise<Void> peerObserved = Promise.promise();
        Map<String, HttpServerRequest> requests = new ConcurrentHashMap<>();
        AtomicInteger arrivals = new AtomicInteger();
        AtomicInteger responseHeaders = new AtomicInteger();
        vertx.createHttpServer(new HttpServerOptions().setHttp2MultiplexImplementation(multiplex))
            .requestHandler(HttpContextTransport.handler(request -> testContext.verify(() -> {
                assertEquals(version, request.version());
                ContextInternal scope = ContextInternal.current();
                FlowId remote = FlowId.builder().withId("server" + request.path()).build();
                Storage.put("flow_id", remote);
                requests.put(request.path(), request);
                if (request.path().equals("/cancel")) {
                    request.response().exceptionHandler(ignored -> { });
                    request.response().closeHandler(ignored -> peerObserved.tryComplete());
                    if (headersFirst) {
                        ContextPropagation.bind(bothArrived.future())
                            .compose(ignored -> request.response().setChunked(true).write("partial-body"))
                            .onFailure(testContext::failNow);
                    }
                } else {
                    request.response().exceptionHandler(testContext::failNow);
                    ContextPropagation.bind(bothArrived.future())
                        .compose(ignored -> ContextPropagation.bind(peerObserved.future()))
                        .compose(ignored -> {
                            assertSame(scope, ContextInternal.current());
                            assertSame(remote, Storage.get("flow_id"));
                            return request.response().end("healthy");
                        }).onFailure(testContext::failNow);
                }
                if (arrivals.incrementAndGet() == 2) {
                    if (version == HttpVersion.HTTP_2) {
                        assertSame(requests.get("/cancel").connection(), requests.get("/healthy").connection());
                    } else {
                        assertNotSame(requests.get("/cancel").connection(), requests.get("/healthy").connection());
                    }
                    bothArrived.complete();
                }
            }))).listen(0, "127.0.0.1").compose(server -> {
                HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + server.actualPort()));
                client.addInterceptor(event -> {
                    if (event.phase() == ClientPhase.SEND_REQUEST && event.clientRequest().getURI().equals("/cancel")) {
                        nativeRequest.tryComplete(event.clientRequest());
                    } else if (event.phase() == ClientPhase.RECEIVE_RESPONSE && event.requestOptions().getURI().equals("/cancel")) {
                        responseHeaders.incrementAndGet();
                    } else if (event.phase() == ClientPhase.FAILURE && event.requestOptions().getURI().equals("/cancel")) {
                        nativeFailure.tryComplete(event.failure());
                    }
                    event.next();
                });
                List<Future<String>> deployments = new ArrayList<>();
                deployments.add(vertx.deployVerticle(new VerticleBase() {
                    @Override
                    public Future<?> start() {
                        ContextInternal caller = ContextInternal.current();
                        FlowId original = FlowId.builder().withId("cancel-original").build();
                        if (localState.equals("value")) {
                            Storage.put("flow_id", original);
                        } else if (localState.equals("removed")) {
                            Storage.remove("flow_id");
                        }
                        CancellationSink sink = new CancellationSink(caller, model, localState, original, removal, nativeRequest);
                        var pending = client.get(server.actualPort(), "127.0.0.1", "/cancel").timeout(3_000)
                            .as(BodyCodec.pipe(sink)).send();
                        Future<Void> ready = headersFirst ? caller.succeededFuture()
                            : ContextPropagation.bind(bothArrived.future()).<Void>map(ignored -> {
                                assertFalse(pending.isComplete());
                                assertEquals(0, responseHeaders.get());
                                sink.prepareCancellation(false);
                                sink.cancelLater();
                                return null;
                            });
                        return ready.compose(ignored -> pending).transform(result -> {
                                sink.assertChangedState();
                                assertTrue(result.failed());
                                assertSame(nativeFailure.future().result(), result.cause());
                                assertFalse(result.cause() instanceof TimeoutException);
                                assertFalse(result.cause() instanceof ContextPropagationException);
                                if (version == HttpVersion.HTTP_2) {
                                    assertEquals(8, assertInstanceOf(StreamResetException.class, result.cause()).getCode());
                                }
                                assertEquals(headersFirst ? 1 : 0, responseHeaders.get());
                                assertEquals(headersFirst, sink.ended);
                                if (headersFirst) {
                                    assertTrue(sink.writes > 0);
                                    assertFalse(sink.body.toString().isEmpty());
                                    assertTrue("partial-body".startsWith(sink.body.toString()));
                                } else {
                                    assertEquals(0, sink.writes);
                                    assertEquals(0, sink.body.length());
                                }
                                return ContextPropagation.bind(Future.join(sink.cancelled.future(), peerObserved.future()))
                                    .compose(ignored -> {
                                        assertTrue(sink.cancelled.future().result());
                                        sink.assertChangedState();
                                        return vertx.timer(1).map(tick -> {
                                            sink.assertChangedState();
                                            return tick;
                                        });
                                    });
                            });
                    }
                }, new DeploymentOptions().setThreadingModel(model)));
                deployments.add(vertx.deployVerticle(new VerticleBase() {
                    @Override
                    public Future<?> start() {
                        ContextInternal caller = ContextInternal.current();
                        FlowId original = FlowId.builder().withId("healthy-original").build();
                        Storage.put("flow_id", original);
                        return client.get(server.actualPort(), "127.0.0.1", "/healthy").timeout(3_000).send()
                            .compose(response -> {
                                assertEquals(version, response.version());
                                assertEquals("healthy", response.bodyAsString());
                                assertTrue(peerObserved.future().succeeded());
                                assertCaller(caller, model);
                                assertSame(original, Storage.get("flow_id"));
                                return vertx.timer(1).map(tick -> {
                                    assertCaller(caller, model);
                                    assertSame(original, Storage.get("flow_id"));
                                    return tick;
                                });
                            });
                    }
                }, new DeploymentOptions().setThreadingModel(model)));
                return Future.join(deployments);
            }).andThen(ignored -> client.close()).onComplete(testContext.succeedingThenComplete());
    }

    private static final class CancellationSink implements WriteStream<Buffer> {
        private final ContextInternal caller;
        private final ThreadingModel model;
        private final String initialState;
        private final FlowId original;
        private final boolean removal;
        private final Promise<HttpClientRequest> nativeRequest;
        private final FlowId changed = FlowId.builder().withId("changed-before-cancel").build();
        private final Promise<Boolean> cancelled;
        private final Buffer body = Buffer.buffer();
        private int writes;
        private boolean ended;
        private boolean prepared;

        private CancellationSink(ContextInternal caller, ThreadingModel model, String initialState, FlowId original,
                boolean removal, Promise<HttpClientRequest> nativeRequest) {
            this.caller = caller;
            this.model = model;
            this.initialState = initialState;
            this.original = original;
            this.removal = removal;
            this.nativeRequest = nativeRequest;
            cancelled = caller.promise();
        }

        @Override
        public Future<Void> write(Buffer data) {
            assertCaller(caller, model);
            assertFalse(ended);
            boolean first = writes == 0;
            if (first) {
                prepareCancellation(true);
            }
            assertChangedState();
            writes++;
            body.appendBuffer(data);
            if (first) {
                cancelLater();
            }
            return caller.succeededFuture();
        }

        @Override
        public Future<Void> end() {
            assertChangedState();
            assertFalse(ended);
            ended = true;
            return caller.succeededFuture();
        }

        private void prepareCancellation(boolean headersReceived) {
            assertCaller(caller, model);
            assertFalse(prepared);
            if (initialState.equals("value")) {
                assertSame(original, Storage.get("flow_id"));
            } else if (initialState.equals("removed")) {
                assertNull(Storage.get("flow_id"));
                assertTrue(DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
            } else if (headersReceived) {
                assertEquals("server/cancel", ((FlowId) Storage.get("flow_id")).getId());
            } else {
                assertNull(Storage.get("flow_id"));
                assertNull(DefaultStorage.getEntry(caller, "flow_id"));
            }
            if (removal) {
                Storage.remove("flow_id");
            } else {
                Storage.put("flow_id", changed);
            }
            prepared = true;
        }

        private void cancelLater() {
            // Defer cancellation until the current write or control callback has returned.
            caller.runOnContext(ignored -> {
                assertChangedState();
                nativeRequest.future().result().cancel().map(result -> {
                    assertChangedState();
                    return result;
                }).onComplete(cancelled);
            });
        }

        private void assertChangedState() {
            assertCaller(caller, model);
            assertTrue(prepared);
            if (removal) {
                assertNull(Storage.get("flow_id"));
                assertTrue(DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
            } else {
                assertSame(changed, Storage.get("flow_id"));
                assertFalse(DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
            }
        }

        @Override
        public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        @Override
        public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
            return this;
        }

        @Override
        public boolean writeQueueFull() {
            return false;
        }

        @Override
        public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
            return this;
        }
    }

    private static void assertCaller(ContextInternal caller, ThreadingModel model) {
        assertSame(caller, ContextInternal.current());
        assertEquals(model, caller.threadingModel());
        assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
    }

    private static Stream<Arguments> cancellationModes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of(HttpVersion.HTTP_1_1, HttpVersion.HTTP_2)
                .flatMap(version -> (version == HttpVersion.HTTP_1_1 ? Stream.of(false) : Stream.of(false, true))
                    .flatMap(multiplex -> Stream.of("empty", "value", "removed").flatMap(state -> Stream.of(false, true)
                        .flatMap(removal -> Stream.of(false, true)
                            .map(headers -> Arguments.of(model, version, multiplex, state, removal, headers)))))));
    }
}
