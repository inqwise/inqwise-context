package com.inqwise.context;

import java.net.URI;
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
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.streams.WriteStream;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class HttpBackpressureContextTest {
    private enum Completion {
        SUCCESS, WRITE_FAILURE, END_FAILURE
    }

    @ParameterizedTest
    @MethodSource("backpressureModes")
    void response_asyncSink_preservesContextDuringPauseDrainAndNativeFailures(
            ThreadingModel model, HttpVersion version, boolean multiplex, Completion completion, boolean pauseRemoval, boolean closeSink,
            Vertx vertx, VertxTestContext testContext) {
        vertx.exceptionHandler(testContext::failNow);
        WebClientOptions options = new WebClientOptions().setProtocolVersion(version).setHttp2ClearTextUpgrade(false);
        options.setHttp2MultiplexImplementation(multiplex);
        WebClient client = WebClient.create(vertx, options);
        Promise<Void> pressureEntered = Promise.promise();
        Promise<Void> serverSent = Promise.promise();
        Promise<Void> secondMutation = Promise.promise();
        Promise<Void> serverVerified = Promise.promise();
        vertx.createHttpServer(new HttpServerOptions().setHttp2MultiplexImplementation(multiplex))
            .requestHandler(HttpContextTransport.handler(request -> {
                assertEquals(version, request.version());
                ContextInternal scope = ContextInternal.current();
                FlowId remote = FlowId.builder().withId("server-response").build();
                Storage.put("flow_id", remote);
                Future<Void> sent = request.response().setChunked(true).write("one-")
                    .compose(ignored -> ContextPropagation.bind(pressureEntered.future()))
                    .compose(ignored -> request.response().write("two-"))
                    .compose(ignored -> request.response().end("three"));
                sent.onComplete(serverSent);
                sent.compose(ignored -> ContextPropagation.bind(secondMutation.future()))
                    .<Void>map(ignored -> {
                        assertSame(scope, ContextInternal.current());
                        assertSame(remote, Storage.get("flow_id"));
                        return null;
                    }).onComplete(serverVerified);
            })).listen(0, "127.0.0.1").compose(server -> {
                HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + server.actualPort()));
                return vertx.deployVerticle(new VerticleBase() {
                    @Override
                    public Future<?> start() {
                        ContextInternal caller = ContextInternal.current();
                        assertNull(Storage.get("flow_id"));
                        ControlledSink sink = new ControlledSink(vertx, caller, model, completion, pauseRemoval,
                            pressureEntered, serverSent, secondMutation, testContext);
                        BodyCodec<Void> codec = closeSink ? BodyCodec.pipe(sink) : BodyCodec.pipe(sink, false);
                        var pending = client.get(server.actualPort(), "127.0.0.1", "/").timeout(3_000)
                            .as(codec).send();
                        return pending.transform(result -> {
                            sink.assertFinalState();
                            boolean httpFailure = completion == Completion.WRITE_FAILURE
                                || (closeSink && completion == Completion.END_FAILURE);
                            assertEquals(httpFailure, result.failed());
                            if (result.failed()) {
                                assertSame(sink.failure, result.cause());
                            } else {
                                assertEquals(version, result.result().version());
                                assertNull(result.result().body());
                            }
                            assertEquals(closeSink, sink.ended);
                            Future<Void> manualEnd = closeSink ? null : sink.end();
                            return ContextPropagation.bind(Future.join(serverVerified.future(), sink.endObserved.future()))
                                .compose(ignored -> {
                                    sink.assertFinalState();
                                    assertTrue(sink.pressureChecked);
                                    assertTrue(sink.drainRegistered);
                                    assertTrue(sink.ended);
                                    assertFalse(sink.full);
                                    assertEquals(completion != Completion.WRITE_FAILURE, sink.drainInvoked);
                                    assertSame(completion == Completion.END_FAILURE ? sink.failure : null,
                                        sink.endObserved.future().result());
                                    if (manualEnd != null) {
                                        assertTrue(manualEnd.isComplete());
                                        assertEquals(completion == Completion.END_FAILURE, manualEnd.failed());
                                        assertSame(completion == Completion.END_FAILURE ? sink.failure : null, manualEnd.cause());
                                    }
                                    assertTrue(pending.isComplete());
                                    assertEquals(httpFailure, pending.failed());
                                    if (httpFailure) {
                                        assertSame(sink.failure, pending.cause());
                                    } else {
                                        assertSame(result.result(), pending.result());
                                    }
                                    if (completion == Completion.WRITE_FAILURE) {
                                        assertEquals(1, sink.writes);
                                        assertEquals("one-", sink.body.toString());
                                    } else {
                                        assertTrue(sink.writes >= 2);
                                        assertEquals("one-two-three", sink.body.toString());
                                    }
                                    return vertx.timer(1).map(tick -> {
                                        sink.assertFinalState();
                                        return tick;
                                    });
                                });
                        });
                    }
                }, new DeploymentOptions().setThreadingModel(model));
            }).andThen(ignored -> client.close()).onComplete(testContext.succeedingThenComplete());
    }

    // One deferred write provides deterministic pressure; Vert.x owns pause/resume and body piping.
    private static final class ControlledSink implements WriteStream<Buffer> {
        private final Vertx vertx;
        private final ContextInternal caller;
        private final ThreadingModel model;
        private final Completion completion;
        private final boolean pauseRemoval;
        private final Promise<Void> pressureEntered;
        private final Promise<Void> serverSent;
        private final Promise<Void> secondMutation;
        private final VertxTestContext testContext;
        private final FlowId firstChange = FlowId.builder().withId("first-change").build();
        private final FlowId finalChange = FlowId.builder().withId("final-change").build();
        private final IllegalStateException failure;
        private final Promise<Void> firstWrite;
        private final Promise<Throwable> endObserved;
        private final Buffer body = Buffer.buffer();
        private int writes;
        private boolean full;
        private boolean pressureChecked;
        private boolean drainRegistered;
        private boolean drainInvoked;
        private boolean ended;
        private boolean finalPhase;

        private ControlledSink(Vertx vertx, ContextInternal caller, ThreadingModel model, Completion completion,
                boolean pauseRemoval, Promise<Void> pressureEntered, Promise<Void> serverSent,
                Promise<Void> secondMutation, VertxTestContext testContext) {
            this.vertx = vertx;
            this.caller = caller;
            this.model = model;
            this.completion = completion;
            this.pauseRemoval = pauseRemoval;
            this.pressureEntered = pressureEntered;
            this.serverSent = serverSent;
            this.secondMutation = secondMutation;
            this.testContext = testContext;
            failure = new IllegalStateException("Controlled sink " + completion);
            firstWrite = caller.promise();
            endObserved = caller.promise();
        }

        @Override
        public Future<Void> write(Buffer data) {
            assertCaller();
            assertFalse(ended);
            if (writes == 0) {
                assertEquals("server-response", ((FlowId) Storage.get("flow_id")).getId());
                change(!pauseRemoval, firstChange);
                full = true;
            } else {
                assertFinalState();
            }
            writes++;
            body.appendBuffer(data);
            return writes == 1 ? firstWrite.future() : caller.succeededFuture();
        }

        @Override
        public boolean writeQueueFull() {
            if (full) {
                pressureChecked = true;
            }
            return full;
        }

        @Override
        public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
            if (handler == null) {
                return this;
            }
            assertCaller();
            assertTrue(full);
            assertTrue(pressureChecked);
            assertFalse(drainRegistered);
            drainRegistered = true;
            pressureEntered.complete();
            ContextPropagation.bind(serverSent.future()).compose(ignored -> vertx.timer(1))
                .map(ignored -> {
                    assertCaller();
                    assertEquals(1, writes);
                    assertTrue(full);
                    assertFalse(firstWrite.future().isComplete());
                    assertStored(!pauseRemoval, firstChange);
                    change(pauseRemoval, finalChange);
                    finalPhase = true;
                    full = false;
                    secondMutation.complete();
                    if (completion == Completion.WRITE_FAILURE) {
                        firstWrite.fail(failure);
                    } else {
                        firstWrite.complete();
                        drainInvoked = true;
                        handler.handle(null);
                    }
                    return ignored;
                }).onFailure(testContext::failNow);
            return this;
        }

        @Override
        public Future<Void> end() {
            assertFinalState();
            assertFalse(ended);
            ended = true;
            Future<Void> result = vertx.timer(1).<Void>map(ignored -> {
                assertFinalState();
                if (completion == Completion.END_FAILURE) {
                    throw failure;
                }
                return null;
            });
            result.onComplete(outcome -> endObserved.complete(outcome.cause()));
            return result;
        }

        private void assertCaller() {
            assertSame(caller, ContextInternal.current());
            assertEquals(model, caller.threadingModel());
            assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
        }

        private void assertFinalState() {
            assertCaller();
            assertTrue(finalPhase);
            assertStored(pauseRemoval, finalChange);
        }

        private void assertStored(boolean removal, FlowId value) {
            if (removal) {
                assertNull(Storage.get("flow_id"));
            } else {
                assertSame(value, Storage.get("flow_id"));
            }
            assertEquals(removal, DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
        }

        private void change(boolean removal, FlowId value) {
            if (removal) {
                Storage.remove("flow_id");
            } else {
                Storage.put("flow_id", value);
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
    }

    private static Stream<Arguments> backpressureModes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of(HttpVersion.HTTP_1_1, HttpVersion.HTTP_2)
                .flatMap(version -> (version == HttpVersion.HTTP_1_1 ? Stream.of(false) : Stream.of(false, true))
                    .flatMap(multiplex -> Stream.of(Completion.values()).flatMap(completion -> Stream.of(false, true)
                        .flatMap(removal -> Stream.of(false, true)
                            .map(closeSink -> Arguments.of(model, version, multiplex, completion, removal, closeSink)))))));
    }
}
