package com.inqwise.context;

import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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
import static org.junit.jupiter.api.Assertions.assertSame;

@ExtendWith(VertxExtension.class)
@Timeout(10)
class CircuitBreakerContextTest {
    @Test
    void execute_asyncSuccess_preservesInstanceInCommandAndReturn(Vertx vertx, VertxTestContext context) {
        CircuitBreaker breaker = CircuitBreaker.create("success", vertx);
        inFlow(vertx, "caller", item -> breaker.execute(() -> {
            assertContext(item);
            return vertx.executeBlocking(() -> {
                assertContext(item);
                return item;
            });
        }).map(returned -> {
            assertContext(item);
            assertSame(item, returned);
            return returned;
        })).andThen(ignored -> breaker.close()).onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void execute_failureOrTimeout_preservesInstanceInFallbackAndReturn(
            boolean timeout, Vertx vertx, VertxTestContext context) {
        CircuitBreaker breaker = CircuitBreaker.create("fallback", vertx,
            new CircuitBreakerOptions().setFallbackOnFailure(true).setTimeout(25));
        inFlow(vertx, "caller", item -> breaker.executeWithFallback(() -> {
            assertContext(item);
            return timeout ? Promise.<String>promise().future()
                : Future.<String>failedFuture("controlled failure");
        }, failure -> {
            assertContext(item);
            return "fallback";
        }).map(result -> {
            assertContext(item);
            assertEquals("fallback", result);
            return result;
        })).andThen(ignored -> breaker.close()).onComplete(context.succeedingThenComplete());
    }

    @Test
    void execute_failedOperation_preservesInstanceInRecovery(Vertx vertx, VertxTestContext context) {
        CircuitBreaker breaker = CircuitBreaker.create("failure", vertx);
        IllegalStateException failure = new IllegalStateException("controlled failure");
        inFlow(vertx, "caller", item -> breaker.execute(() -> {
            assertContext(item);
            return Future.failedFuture(failure);
        }).recover(cause -> {
            assertContext(item);
            assertSame(failure, cause);
            return Future.succeededFuture(item);
        })).andThen(ignored -> breaker.close()).onComplete(context.succeedingThenComplete());
    }

    @Test
    void execute_contextUpdatedInOperation_returnsSameReplacementToCaller(Vertx vertx, VertxTestContext context) {
        CircuitBreaker breaker = CircuitBreaker.create("backward", vertx);
        vertx.runOnContext(ignored -> context.verify(() -> {
            ClientContextTestSupport.Item original = new ClientContextTestSupport.Item("original", Vertx.currentContext());
            ClientContextTestSupport.Item replacement = new ClientContextTestSupport.Item("replacement", Vertx.currentContext());
            Storage.put(ClientContextTestSupport.KEY, original);
            breaker.execute(() -> {
                assertContext(original);
                Storage.put(ClientContextTestSupport.KEY, replacement);
                return Future.succeededFuture(replacement);
            }).onComplete(context.succeeding(returned -> context.verify(() -> {
                assertContext(replacement);
                assertSame(replacement, returned);
                breaker.close();
                context.completeNow();
            })));
        }));
    }
}
