package com.inqwise.context;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(VertxExtension.class)
@Timeout(10)
class EventBusPropagationTest {
    private static final String FLOW_ID = "flow_id";
    private final FlowIdService service = new FlowIdService();

    @Test
    void request_twoHops_propagatesUpdatedContextAndReply(Vertx vertx, VertxTestContext context) {
        var sink = vertx.eventBus().<String>consumer("sink", message -> context.verify(() -> {
            assertEquals("updated", ((FlowId) Storage.get(FLOW_ID)).getId());
            assertEquals(List.of(encoded("updated")), message.headers().getAll(FLOW_ID));
            message.reply("done");
        }));
        var relay = vertx.eventBus().<String>consumer("relay", message -> context.verify(() -> {
            assertEquals("original", ((FlowId) Storage.get(FLOW_ID)).getId());
            Storage.put(FLOW_ID, FlowId.builder().withId("updated").build());
            DeliveryOptions forwarded = new DeliveryOptions().setHeaders(message.headers().copy());
            vertx.eventBus().<String>request("sink", message.body(), forwarded)
                .onComplete(context.succeeding(reply -> context.verify(() -> {
                    assertEquals("updated", ((FlowId) Storage.get(FLOW_ID)).getId());
                    message.reply(reply.body());
                })));
        }));

        Future.all(sink.completion(), relay.completion())
            .compose(ignored -> vertx.eventBus().<String>request("relay", "payload", options("original")))
            .onComplete(context.succeeding(reply -> context.verify(() -> {
                assertEquals("done", reply.body());
                assertEquals(encoded("updated"), reply.headers().get(FLOW_ID));
                context.completeNow();
            })));
    }

    @Test
    void request_overlappingMessages_keepsContextIsolated(Vertx vertx, VertxTestContext context) {
        AtomicInteger entered = new AtomicInteger();
        Promise<Void> bothEntered = Promise.promise();
        var consumer = vertx.eventBus().<String>consumer("overlap", message -> context.verify(() -> {
            assertEquals(message.body(), ((FlowId) Storage.get(FLOW_ID)).getId());
            Storage.put(FLOW_ID, FlowId.builder().withId(message.body() + "-updated").build());
            Context messageContext = Vertx.currentContext();
            bothEntered.future().onComplete(context.succeeding(ignored -> messageContext.runOnContext(resumed -> context.verify(() -> {
                String current = ((FlowId) Storage.get(FLOW_ID)).getId();
                assertEquals(message.body() + "-updated", current);
                message.reply(current);
            }))));
            if (entered.incrementAndGet() == 2) {
                bothEntered.complete();
            }
        }));

        consumer.completion().onComplete(context.succeeding(ignored -> {
            Future<Message<String>> first = vertx.eventBus().request("overlap", "first", options("first"));
            Future<Message<String>> second = vertx.eventBus().request("overlap", "second", options("second"));
            Future.all(first, second).onComplete(context.succeeding(results -> context.verify(() -> {
                assertEquals("first-updated", first.result().body());
                assertEquals("second-updated", second.result().body());
                assertEquals(encoded("first-updated"), first.result().headers().get(FLOW_ID));
                assertEquals(encoded("second-updated"), second.result().headers().get(FLOW_ID));
                context.completeNow();
            })));
        }));
    }

    @Test
    void request_messageWithoutMetadata_doesNotInheritPreviousMessage(Vertx vertx, VertxTestContext context) {
        var consumer = vertx.eventBus().<String>consumer("sequential", message -> context.verify(() -> {
            if (message.body().equals("with-context")) {
                assertEquals("first", ((FlowId) Storage.get(FLOW_ID)).getId());
            } else {
                assertNull(message.headers().get(FLOW_ID));
                assertNull(Storage.get(FLOW_ID));
            }
            message.reply("done");
        }));

        consumer.completion()
            .compose(ignored -> vertx.eventBus().request("sequential", "with-context", options("first")))
            .onComplete(context.succeeding(ignored -> {
                // Start an independent flow instead of continuing the first reply's context.
                CompletableFuture.runAsync(() -> vertx.eventBus().request("sequential", "without-context")
                    .onComplete(context.succeeding(reply -> context.verify(() -> {
                        assertNull(reply.headers().get(FLOW_ID));
                        context.completeNow();
                    })))).exceptionally(error -> {
                        context.failNow(error);
                        return null;
                    });
            }));
    }

    private DeliveryOptions options(String id) {
        return new DeliveryOptions().addHeader(FLOW_ID, encoded(id));
    }

    private String encoded(String id) {
        return service.marshalToString(FlowId.builder().withId(id).build());
    }
}
