package com.inqwise.context;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.ThreadingModel;
import io.vertx.core.Timer;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.DecodeException;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class VirtualThreadAwaitTest {
    @ParameterizedTest
    @MethodSource("foreignModes")
    void await_foreignCompletion_preservesContextAndStateOnSuccessOrFailure(boolean nativeFuture,
            boolean failure, boolean removed, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        Promise<String> nativePromise = ((ContextInternal) vertx.getOrCreateContext()).promise();
        CompletableFuture<String> stage = new CompletableFuture<>();
        IllegalStateException expected = new IllegalStateException("Controlled foreign completion failure");
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                ContextInternal caller = ContextInternal.current();
                Thread awaitingThread = Thread.currentThread();
                FlowId original = FlowId.builder().withId("caller").build();
                initialize(removed ? "removed" : "value", original);
                assertFrame(caller);
                Future<String> bound = nativeFuture ? ContextPropagation.bind(nativePromise.future()) : ContextPropagation.bind(stage);
                // Completion cannot run on this context until await suspends the current task.
                caller.runOnContext(ignored -> {
                    try {
                        assertFrame(caller);
                        assertNotSame(awaitingThread, Thread.currentThread());
                        assertStored(removed ? null : original, removed);
                        if (nativeFuture) {
                            if (failure) {
                                nativePromise.fail(expected);
                            } else {
                                nativePromise.complete("done");
                            }
                        } else if (failure) {
                            stage.completeExceptionally(expected);
                        } else {
                            stage.complete("done");
                        }
                    } catch (Throwable assertion) {
                        nativePromise.tryFail(assertion);
                        stage.completeExceptionally(assertion);
                    }
                });
                assertFalse(bound.isComplete());
                if (failure) {
                    assertSame(expected, assertThrows(IllegalStateException.class, bound::await));
                } else {
                    assertEquals("done", bound.await());
                }
                assertSame(awaitingThread, Thread.currentThread());
                assertFrame(caller);
                assertStored(removed ? null : original, removed);
                FlowId replacement = FlowId.builder().withId("after-await").build();
                Storage.put("flow_id", replacement);
                vertx.timer(1).await();
                assertFrame(caller);
                assertSame(replacement, Storage.get("flow_id"));
                return Future.succeededFuture();
            }
        }, virtual()).onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @MethodSource("eventBusModes")
    void await_eventBusCallAndReturn_preservesCallerPolicy(String state, boolean remoteRemoval,
            Vertx vertx, VertxTestContext testContext) {
        vertx.exceptionHandler(testContext::failNow);
        FlowId original = FlowId.builder().withId("caller").build();
        FlowId remote = FlowId.builder().withId("eventbus-remote").build();
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                return vertx.eventBus().<String>consumer("await-flow", message -> {
                    try {
                        ContextInternal receiver = ContextInternal.current();
                        assertFrame(receiver);
                        assertIncoming(state, original);
                        FlowId incoming = (FlowId) Storage.get("flow_id");
                        vertx.timer(1).await();
                        assertFrame(receiver);
                        assertSame(incoming, Storage.get("flow_id"));
                        change(remoteRemoval, remote);
                        message.reply("done");
                    } catch (Throwable failure) {
                        message.fail(500, failure.getClass().getName());
                        testContext.failNow(failure);
                    }
                }).completion();
            }
        }, virtual()).compose(ignored -> vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                ContextInternal caller = ContextInternal.current();
                initialize(state, original);
                var reply = Future.await(vertx.eventBus().<String>request("await-flow", "payload",
                    new DeliveryOptions().setSendTimeout(3_000)));
                assertEquals("done", reply.body());
                assertFrame(caller);
                assertReturned(state, remoteRemoval, original, remote);
                String wire = reply.headers().get("flow_id");
                if (remoteRemoval) {
                    assertTrue(new FlowIdService().isRemoval(wire));
                } else {
                    assertEquals(remote.toJson(), ((FlowId) new FlowIdService().unmarshalFormString(wire)).toJson());
                }
                return Future.succeededFuture();
            }
        }, virtual())).onComplete(testContext.succeedingThenComplete());
    }

    @ParameterizedTest
    @MethodSource("httpModes")
    void await_httpResponseOrBodyFailure_importsHeadersWithoutChangingCallerPolicy(String state, String action,
            Vertx vertx, VertxTestContext testContext) {
        vertx.exceptionHandler(testContext::failNow);
        WebClient client = WebClient.create(vertx);
        FlowId original = FlowId.builder().withId("caller").build();
        FlowId remote = FlowId.builder().withId("http-remote").build();
        Promise<HttpServer> ready = Promise.promise();
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                return vertx.createHttpServer().requestHandler(HttpContextTransport.handler(request -> {
                    try {
                        ContextInternal receiver = ContextInternal.current();
                        assertFrame(receiver);
                        assertIncoming(state, original);
                        FlowId incoming = (FlowId) Storage.get("flow_id");
                        vertx.timer(1).await();
                        assertFrame(receiver);
                        assertSame(incoming, Storage.get("flow_id"));
                        change(action.equals("remove"), remote);
                        request.response().putHeader("Content-Type", "application/json")
                            .end(action.equals("invalid-body") ? "not-json" : "{\"done\":true}");
                    } catch (Throwable failure) {
                        testContext.failNow(failure);
                        request.response().setStatusCode(500).end();
                    }
                })).listen(0, "127.0.0.1").andThen(ready);
            }
        }, virtual()).compose(ignored -> ready.future()).compose(server -> {
            HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + server.actualPort()));
            return vertx.deployVerticle(new VerticleBase() {
                @Override
                public Future<?> start() {
                    ContextInternal caller = ContextInternal.current();
                    initialize(state, original);
                    var request = client.get(server.actualPort(), "127.0.0.1", "/").timeout(3_000).as(BodyCodec.jsonObject());
                    if (action.equals("invalid-body")) {
                        assertThrows(DecodeException.class, () -> request.send().await());
                    } else {
                        var response = request.send().await();
                        assertEquals(200, response.statusCode());
                        assertTrue(response.body().getBoolean("done"));
                    }
                    assertFrame(caller);
                    assertReturned(state, action.equals("remove"), original, remote);
                    return Future.succeededFuture();
                }
            }, virtual());
        }).andThen(ignored -> client.close()).onComplete(testContext.succeedingThenComplete());
    }

    @ParameterizedTest
    @ValueSource(strings = {"timer", "cancel", "periodic"})
    void await_timerCompletionOrCancellation_retainsVirtualContext(String api, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                ContextInternal caller = ContextInternal.current();
                FlowId original = FlowId.builder().withId("await-timer").build();
                Storage.put("flow_id", original);
                if (api.equals("periodic")) {
                    Promise<Void> completion = Promise.promise();
                    AtomicInteger ticks = new AtomicInteger();
                    vertx.setPeriodic(1, ContextPropagation.bindHandler(timerId -> {
                        try {
                            assertFrame(caller);
                            assertSame(original, Storage.get("flow_id"));
                            if (ticks.incrementAndGet() == 3) {
                                assertTrue(vertx.cancelTimer(timerId));
                                completion.complete();
                            }
                        } catch (Throwable failure) {
                            vertx.cancelTimer(timerId);
                            completion.tryFail(failure);
                        }
                    }));
                    ContextPropagation.bind(completion.future()).await();
                    assertEquals(3, ticks.get());
                } else {
                    boolean cancel = api.equals("cancel");
                    Timer timer = vertx.timer(cancel ? 60_000 : 1);
                    if (cancel) {
                        assertTrue(timer.cancel());
                        assertThrows(CancellationException.class, timer::await);
                    } else {
                        timer.await();
                    }
                }
                assertFrame(caller);
                assertSame(original, Storage.get("flow_id"));
                return Future.succeededFuture();
            }
        }, virtual()).onComplete(context.succeedingThenComplete());
    }

    private static void assertFrame(ContextInternal expected) {
        assertTrue(Thread.currentThread().isVirtual());
        assertSame(expected, ContextInternal.current());
        assertEquals(ThreadingModel.VIRTUAL_THREAD, expected.threadingModel());
    }

    private static void initialize(String state, FlowId value) {
        if (state.equals("value")) {
            Storage.put("flow_id", value);
        } else if (state.equals("removed")) {
            Storage.remove("flow_id");
        }
    }

    private static void change(boolean removal, FlowId value) {
        if (removal) {
            Storage.remove("flow_id");
        } else {
            Storage.put("flow_id", value);
        }
    }

    private static void assertIncoming(String state, FlowId original) {
        if (state.equals("value")) {
            FlowId received = (FlowId) Storage.get("flow_id");
            assertNotSame(original, received);
            assertEquals(original.toJson(), received.toJson());
        } else {
            assertStored(null, state.equals("removed"));
        }
    }

    private static void assertReturned(String state, boolean remoteRemoval, FlowId original, FlowId remote) {
        if (state.equals("value")) {
            assertSame(original, Storage.get("flow_id"));
        } else if (state.equals("removed") || remoteRemoval) {
            assertStored(null, true);
        } else {
            FlowId received = (FlowId) Storage.get("flow_id");
            assertNotSame(remote, received);
            assertEquals(remote.toJson(), received.toJson());
        }
    }

    private static void assertStored(FlowId expected, boolean removal) {
        if (expected != null) {
            assertSame(expected, Storage.get("flow_id"));
        } else {
            assertNull(Storage.get("flow_id"));
            MultiMap headers = MultiMap.caseInsensitiveMultiMap();
            ContextTransport.writeTo(new HttpContextCarrier(headers));
            List<String> values = new HttpContextCarrier(headers).getAll("flow_id");
            if (removal) {
                assertEquals(1, values.size());
                assertTrue(new FlowIdService().isRemoval(values.getFirst()));
            } else {
                assertTrue(values.isEmpty());
            }
        }
    }

    private static DeploymentOptions virtual() {
        return new DeploymentOptions().setThreadingModel(ThreadingModel.VIRTUAL_THREAD);
    }

    private static Stream<Arguments> foreignModes() {
        return Stream.of(false, true).flatMap(nativeFuture -> Stream.of(false, true)
            .flatMap(failure -> Stream.of(false, true).map(removed -> Arguments.of(nativeFuture, failure, removed))));
    }

    private static Stream<Arguments> eventBusModes() {
        return Stream.of("value", "removed", "empty").flatMap(state -> Stream.of(false, true)
            .map(removal -> Arguments.of(state, removal)));
    }

    private static Stream<Arguments> httpModes() {
        return Stream.of("value", "removed", "empty").flatMap(state -> Stream.of("replace", "remove", "invalid-body")
            .map(action -> Arguments.of(state, action)));
    }
}
