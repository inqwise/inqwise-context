package com.inqwise.context;

import java.util.function.Function;

import com.inqwise.context.spi.ContextChainDataItem;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;

import static org.junit.jupiter.api.Assertions.assertSame;

final class ClientContextTestSupport {
    static final String KEY = "client-test-item";

    private ClientContextTestSupport() {
    }

    record Item(String id, Context context) implements ContextChainDataItem {
    }

    static void assertContext(Item item) {
        assertSame(item.context(), Vertx.currentContext(), "Client callback changed the caller's context");
        assertSame(item, Storage.get(KEY), "Client call lost or replaced the metadata instance");
    }

    static Future<Void> inFlow(Vertx vertx, String id, Function<Item, Future<?>> operation) {
        Promise<Void> completion = Promise.promise();
        ContextInternal context = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        context.runOnContext(ignored -> {
            Item item = new Item(id, context);
            Storage.put(KEY, item);
            try {
                assertContext(item);
                operation.apply(item).onComplete(result -> {
                    try {
                        assertContext(item);
                        if (result.succeeded()) {
                            completion.complete();
                        } else {
                            completion.fail(result.cause());
                        }
                    } catch (Throwable error) {
                        completion.tryFail(error);
                    }
                });
            } catch (Throwable error) {
                completion.tryFail(error);
            }
        });
        return completion.future();
    }
}
