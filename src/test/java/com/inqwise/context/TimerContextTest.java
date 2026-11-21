package com.inqwise.context;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.ThreadingModel;
import io.vertx.core.Timer;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.inqwise.context.ClientContextTestSupport.assertContext;
import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(10)
class TimerContextTest {
    @ParameterizedTest
    @MethodSource("deploymentTimers")
    void deployedTimer_eachThreadingModel_preservesContextAndStoredInstance(ThreadingModel model,
            String api, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                ContextInternal caller = ContextInternal.current();
                FlowId original = FlowId.builder().withId("deployed-timer").build();
                Storage.put("flow_id", original);
                assertDeployedContext(model, caller, original);
                Promise<Void> completion = Promise.promise();
                AtomicInteger ticks = new AtomicInteger();
                Handler<Long> callback = timerId -> {
                    try {
                        assertDeployedContext(model, caller, original);
                        if (ticks.incrementAndGet() == (api.equals("periodic") ? 3 : 1)) {
                            if (api.equals("periodic")) {
                                assertTrue(vertx.cancelTimer(timerId));
                            }
                            completion.complete();
                        }
                    } catch (Throwable failure) {
                        if (api.equals("periodic")) {
                            vertx.cancelTimer(timerId);
                        }
                        completion.tryFail(failure);
                    }
                };
                switch (api) {
                    case "setTimer" -> vertx.setTimer(1, callback);
                    case "periodic" -> vertx.setPeriodic(1, ContextPropagation.bindHandler(callback));
                    case "timer", "cancel" -> {
                        boolean cancel = api.equals("cancel");
                        Timer timer = vertx.timer(cancel ? 60_000 : 1);
                        timer.onComplete(result -> {
                            try {
                                assertEquals(cancel, result.failed());
                                if (cancel) {
                                    assertInstanceOf(CancellationException.class, result.cause());
                                }
                                callback.handle(-1L);
                            } catch (Throwable failure) {
                                completion.tryFail(failure);
                            }
                        });
                        if (cancel) {
                            assertTrue(timer.cancel());
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown timer API");
                }
                return ContextPropagation.bind(completion.future()).map(done -> {
                    assertDeployedContext(model, caller, original);
                    assertEquals(api.equals("periodic") ? 3 : 1, ticks.get());
                    return done;
                });
            }
        }, new DeploymentOptions().setThreadingModel(model)).onComplete(context.succeedingThenComplete());
    }

    private static void assertDeployedContext(ThreadingModel model, ContextInternal caller, FlowId original) {
        assertSame(caller, ContextInternal.current());
        assertEquals(model, caller.threadingModel());
        assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
        assertSame(original, Storage.get("flow_id"));
    }

    private static Stream<Arguments> deploymentTimers() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of("setTimer", "timer", "cancel", "periodic").map(api -> Arguments.of(model, api)));
    }

    @Test
    void setTimer_callbackAndContinuation_preserveSameInstance(Vertx vertx, VertxTestContext context) {
        inFlow(vertx, "caller", item -> {
            Promise<ClientContextTestSupport.Item> completion = Promise.promise();
            vertx.setTimer(1, timerId -> {
                try {
                    assertContext(item);
                    completion.complete(item);
                } catch (Throwable error) {
                    completion.fail(error);
                }
            });
            return completion.future().map(returned -> {
                assertContext(item);
                assertSame(item, returned);
                return returned;
            });
        }).onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timer_completionOrCancellation_preservesInstance(
            boolean cancel, Vertx vertx, VertxTestContext context) {
        inFlow(vertx, "caller", item -> {
            Timer timer = vertx.timer(cancel ? 60_000 : 1);
            Future<Void> checked = timer.transform(result -> {
                assertContext(item);
                assertEquals(cancel, result.failed());
                if (cancel) {
                    assertInstanceOf(CancellationException.class, result.cause());
                }
                return Future.succeededFuture();
            });
            if (cancel) {
                assertTrue(timer.cancel());
            }
            return checked;
        }).onComplete(context.succeedingThenComplete());
    }

    @Test
    void setPeriodic_boundRepeatedCallbacks_preserveSameInstance(Vertx vertx, VertxTestContext context) {
        inFlow(vertx, "caller", item -> {
            Promise<Void> completion = Promise.promise();
            AtomicInteger ticks = new AtomicInteger();
            vertx.setPeriodic(1, ContextPropagation.bindHandler(timerId -> {
                try {
                    assertContext(item);
                    if (ticks.incrementAndGet() == 3) {
                        assertTrue(vertx.cancelTimer(timerId));
                        completion.complete();
                    }
                } catch (Throwable error) {
                    vertx.cancelTimer(timerId);
                    completion.tryFail(error);
                }
            }));
            return completion.future().map(ignored -> {
                assertContext(item);
                assertEquals(3, ticks.get());
                return item;
            });
        }).onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timer_twoCallerFlows_keepInstancesIsolated(boolean periodic, Vertx vertx, VertxTestContext context) {
        Future<Void> first = timerFlow(vertx, "first", periodic);
        Future<Void> second = timerFlow(vertx, "second", periodic);
        Future.all(first, second).onComplete(context.succeedingThenComplete());
    }

    @Test
    void setPeriodic_withoutAdapter_usesFreshContextWithoutCallerMetadata(Vertx vertx, VertxTestContext context) {
        inFlow(vertx, "caller", item -> {
            Promise<Void> completion = Promise.promise();
            Future<Void> boundResult = ContextPropagation.bind(completion.future());
            vertx.setPeriodic(1, timerId -> {
                vertx.cancelTimer(timerId);
                try {
                    assertNotSame(item.context(), Vertx.currentContext());
                    assertNull(Storage.get(ClientContextTestSupport.KEY));
                    completion.complete();
                } catch (Throwable error) {
                    completion.fail(error);
                }
            });
            return boundResult;
        }).onComplete(context.succeedingThenComplete());
    }

    @Test
    void timer_contextUpdatedInCallback_returnsSameReplacementToCaller(Vertx vertx, VertxTestContext context) {
        vertx.runOnContext(ignored -> context.verify(() -> {
            ClientContextTestSupport.Item original = new ClientContextTestSupport.Item("original", Vertx.currentContext());
            ClientContextTestSupport.Item replacement = new ClientContextTestSupport.Item("replacement", Vertx.currentContext());
            Storage.put(ClientContextTestSupport.KEY, original);
            vertx.timer(1).map(fired -> {
                assertContext(original);
                Storage.put(ClientContextTestSupport.KEY, replacement);
                return replacement;
            }).onComplete(context.succeeding(returned -> context.verify(() -> {
                assertContext(replacement);
                assertSame(replacement, returned);
                context.completeNow();
            })));
        }));
    }

    private Future<Void> timerFlow(Vertx vertx, String id, boolean periodic) {
        return inFlow(vertx, id, item -> {
            if (!periodic) {
                return vertx.timer(1).map(ignored -> {
                    assertContext(item);
                    return item;
                });
            }
            Promise<Void> completion = Promise.promise();
            vertx.setPeriodic(1, ContextPropagation.bindHandler(timerId -> {
                vertx.cancelTimer(timerId);
                try {
                    assertContext(item);
                    completion.complete();
                } catch (Throwable error) {
                    completion.fail(error);
                }
            }));
            return completion.future();
        });
    }
}
