package com.inqwise.context;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.extension.TenantContext;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.ThreadingModel;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class FlowIdContextTest {
    @Test
    void defaultFactory_freshScopes_haveIndependentIdsAndMillisecondTimestamps(Vertx vertx, VertxTestContext test) {
        List<FlowId> created = new CopyOnWriteArrayList<>();
        Future<?> calls = Future.succeededFuture();
        for (int i = 0; i < 2; i++) {
            calls = calls.compose(ignored -> vertx.deployVerticle(new VerticleBase() {
                @Override
                public Future<?> start() {
                    assertNull(FlowIdContext.get());
                    assertThrows(IllegalStateException.class, FlowIdContext::require);
                    FlowId current = FlowIdContext.getOrCreate();
                    assertEquals(current.getId(), UUID.fromString(current.getId()).toString());
                    assertEquals(0, current.getTimestamp().getNano() % 1_000_000);
                    assertSame(current, FlowIdContext.require());
                    assertSame(current, FlowIdContext.getOrCreate());
                    created.add(current);
                    return Future.succeededFuture();
                }
            }));
        }
        calls.onComplete(test.succeeding(done -> test.verify(() -> {
            assertNotSame(created.getFirst(), created.getLast());
            assertTrue(!created.getFirst().getId().equals(created.getLast().getId()));
            test.completeNow();
        })));
    }

    @ParameterizedTest
    @MethodSource("modes")
    void typedApi_eventBusAndTimers_preserveIdentityAndRemovalWithExternalItems(
            ThreadingModel model, boolean removal, Vertx vertx, VertxTestContext test) {
        vertx.exceptionHandler(test::failNow);
        EventBusTransportHandler external = new EventBusTransportHandler(() -> List.of(new TenantContext.Codec()));
        vertx.eventBus().addInboundInterceptor(external::handleInbound);
        vertx.eventBus().addOutboundInterceptor(external::handleOutbound);
        FlowId original = FlowId.builder().withId("caller").build();
        var consumer = vertx.eventBus().<String>consumer("typed", message -> test.verify(() -> {
            assertEquals("caller-tenant", TenantContext.VALUE.require().id());
            if (removal) {
                assertNull(FlowIdContext.get());
                assertThrows(IllegalStateException.class, FlowIdContext::getOrCreate);
            } else {
                assertEquals(original.getId(), FlowIdContext.require().getId());
                assertNotSame(original, FlowIdContext.require());
            }
            FlowIdContext.put(FlowId.builder().withId("remote").build());
            TenantContext.VALUE.put(new TenantContext.Tenant("remote-tenant"));
            message.reply("delivered");
        }));
        consumer.completion().compose(ignored -> vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                ContextInternal caller = ContextInternal.current();
                FlowIdContext.put(original);
                if (removal) {
                    assertSame(original, FlowIdContext.remove());
                    assertThrows(IllegalStateException.class, FlowIdContext::getOrCreate);
                    assertThrows(IllegalStateException.class, () -> FlowIdContext.getOrCreate(() -> original));
                }
                TenantContext.VALUE.put(new TenantContext.Tenant("caller-tenant"));
                return vertx.eventBus().<String>request("typed", "payload").compose(reply -> {
                    assertEquals("delivered", reply.body());
                    assertSame(caller, ContextInternal.current());
                    assertSame(removal ? null : original, FlowIdContext.get());
                    assertEquals("remote-tenant", TenantContext.VALUE.require().id());
                    return vertx.timer(1);
                }).map(tick -> {
                    assertSame(caller, ContextInternal.current());
                    assertEquals(model, caller.threadingModel());
                    assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                    assertSame(removal ? null : original, FlowIdContext.get());
                    if (removal) {
                        assertThrows(IllegalStateException.class, FlowIdContext::getOrCreate);
                        FlowIdContext.put(original);
                        assertSame(original, FlowIdContext.getOrCreate());
                    }
                    return tick;
                });
            }
        }, new DeploymentOptions().setThreadingModel(model))).eventually(consumer::unregister)
            .onComplete(test.succeedingThenComplete());
    }

    private static Stream<Arguments> modes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of(false, true).map(removal -> Arguments.of(model, removal)));
    }
}
