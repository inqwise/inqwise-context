package com.inqwise.context;

import java.util.concurrent.Callable;

import io.vertx.core.Deployable;
import io.vertx.core.Promise;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(10)
class EventBusTransportStarterTest {

    @Test
    @SuppressWarnings("deprecation")
    void createVerticle_unsupportedCreation_failsPromiseImmediately() {
        Promise<Callable<Verticle>> promise = Promise.promise();

        new EventBusTransportStarter().createVerticle(
            "~contextchain:test", getClass().getClassLoader(), promise);

        assertTrue(promise.future().isComplete());
        assertTrue(promise.future().failed());
        assertInstanceOf(UnsupportedOperationException.class, promise.future().cause());
    }

    @Test
    void createVerticle2_unsupportedCreation_failsPromiseImmediately() {
        Promise<Callable<? extends Deployable>> promise = Promise.promise();

        new EventBusTransportStarter().createVerticle2(
            "~contextchain:test", getClass().getClassLoader(), promise);

        assertTrue(promise.future().isComplete());
        assertTrue(promise.future().failed());
        assertInstanceOf(UnsupportedOperationException.class, promise.future().cause());
    }

    @Test
    void deployVerticle_contextTransportPrefix_failsWithoutHanging(Vertx vertx, VertxTestContext context) {
        vertx.deployVerticle("~contextchain:test")
            .onComplete(context.failing(cause -> context.verify(() -> {
                assertInstanceOf(UnsupportedOperationException.class, cause);
                context.completeNow();
            })));
    }
}
