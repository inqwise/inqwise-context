package com.inqwise.context;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Timer;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
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
