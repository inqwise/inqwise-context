package com.inqwise.context;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.inqwise.context.ClientContextTestSupport.assertContext;
import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(10)
class ContextPropagationTest {
    @ParameterizedTest
    @CsvSource({"true,false", "true,true", "false,false", "false,true"})
    void bind_foreignCompletion_preservesCallerOnSuccessAndFailure(
            boolean nativeFuture, boolean fail, Vertx vertx, VertxTestContext context) {
        Promise<String> foreignPromise = ((ContextInternal) vertx.getOrCreateContext()).promise();
        CompletableFuture<String> foreignStage = new CompletableFuture<>();
        IllegalStateException failure = new IllegalStateException("controlled failure");
        inFlow(vertx, "caller", item -> {
            Future<String> bound = nativeFuture ? ContextPropagation.bind(foreignPromise.future())
                : ContextPropagation.bind(foreignStage);
            CompletableFuture.runAsync(() -> {
                if (nativeFuture) {
                    if (fail) {
                        foreignPromise.fail(failure);
                    } else {
                        foreignPromise.complete("result");
                    }
                } else if (fail) {
                    foreignStage.completeExceptionally(failure);
                } else {
                    foreignStage.complete("result");
                }
            });
            return bound.transform(result -> {
                assertContext(item);
                assertEquals(fail, result.failed());
                if (fail) {
                    assertSame(failure, result.cause());
                } else {
                    assertEquals("result", result.result());
                }
                return Future.succeededFuture(item);
            });
        }).onComplete(context.succeedingThenComplete());
    }

    @Test
    void bind_overlappingFlowsAndReversedCompletion_keepsInstancesIsolated(Vertx vertx, VertxTestContext context) {
        Map<String, CompletableFuture<String>> stages = Map.of(
            "first", new CompletableFuture<>(), "second", new CompletableFuture<>());
        AtomicInteger bound = new AtomicInteger();
        Future<Void> first = flow(vertx, "first", stages, bound);
        Future<Void> second = flow(vertx, "second", stages, bound);
        Future.all(first, second).onComplete(context.succeedingThenComplete());
    }

    @Test
    void bind_outsideVertxContext_rejectsBothResultTypes() {
        assertThrows(IllegalStateException.class, () -> ContextPropagation.bind(new CompletableFuture<>()));
        assertThrows(IllegalStateException.class, () -> ContextPropagation.bind(Future.succeededFuture()));
        assertThrows(IllegalStateException.class, () -> ContextPropagation.bindHandler(event -> { }));
    }

    @Test
    void bind_nullResult_rejectsBothResultTypes() {
        assertThrows(NullPointerException.class, () -> ContextPropagation.bind((CompletionStage<Void>) null));
        assertThrows(NullPointerException.class, () -> ContextPropagation.bind((Future<Void>) null));
        assertThrows(NullPointerException.class, () -> ContextPropagation.bindHandler(null));
    }

    @Test
    void bindHandler_foreignThread_dispatchesWithSameCallerInstance(Vertx vertx, VertxTestContext context) {
        inFlow(vertx, "caller", item -> {
            Promise<String> completion = Promise.promise();
            Handler<String> handler = ContextPropagation.bindHandler(event -> {
                try {
                    assertContext(item);
                    completion.complete(event);
                } catch (Throwable error) {
                    completion.fail(error);
                }
            });
            CompletableFuture.runAsync(() -> handler.handle("event"));
            return completion.future().map(event -> {
                assertContext(item);
                assertEquals("event", event);
                return event;
            });
        }).onComplete(context.succeedingThenComplete());
    }

    @Test
    void bindHandler_otherContext_restoresDispatcherWithoutLeakingMetadata(Vertx vertx, VertxTestContext context) {
        inFlow(vertx, "caller", item -> {
            Promise<Void> completion = Promise.promise();
            Future<Void> result = ContextPropagation.bind(completion.future());
            AtomicBoolean called = new AtomicBoolean();
            Handler<Void> handler = ContextPropagation.bindHandler(event -> {
                try {
                    assertContext(item);
                    called.set(true);
                } catch (Throwable error) {
                    completion.tryFail(error);
                }
            });
            ContextInternal dispatcher = ((ContextInternal) Vertx.currentContext()).duplicate();
            dispatcher.runOnContext(ignored -> {
                try {
                    handler.handle(null);
                    assertTrue(called.get());
                    assertSame(dispatcher, Vertx.currentContext());
                    assertNull(Storage.get(ClientContextTestSupport.KEY));
                    completion.tryComplete();
                } catch (Throwable error) {
                    completion.tryFail(error);
                }
            });
            return result;
        }).onComplete(context.succeedingThenComplete());
    }

    private Future<Void> flow(Vertx vertx, String id, Map<String, CompletableFuture<String>> stages,
            AtomicInteger count) {
        return inFlow(vertx, id, item -> {
            Future<String> result = ContextPropagation.bind(stages.get(id));
            if (count.incrementAndGet() == 2) {
                CompletableFuture.runAsync(() -> {
                    stages.get("second").complete("second");
                    stages.get("first").complete("first");
                });
            }
            return result.map(value -> {
                assertContext(item);
                assertEquals(item.id(), value);
                return value;
            });
        });
    }
}
