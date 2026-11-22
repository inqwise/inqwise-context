package com.inqwise.context;

import java.time.Instant;
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
import org.junit.jupiter.params.provider.EnumSource;
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
    void slotUpdate_replacementOmitsTimestamp_doesNotMergeFieldsOrMutateOriginal(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            Instant timestamp = Instant.ofEpochMilli(123L);
            FlowId original = FlowId.builder().withId("original").withTimestamp(timestamp).build();
            FlowId replacement = FlowId.builder().withId("replacement").build();
            FlowIdContext.put(original);
            assertSame(replacement, FlowIdContext.slot().update(current -> {
                assertSame(original, current);
                return replacement;
            }));
            assertSame(replacement, FlowIdContext.require());
            assertNull(FlowIdContext.require().getTimestamp());
            assertEquals("original", original.getId());
            assertEquals(timestamp, original.getTimestamp());
            test.completeNow();
        }));
    }

    @ParameterizedTest
    @EnumSource(value = ThreadingModel.class, names = {"EVENT_LOOP", "WORKER", "VIRTUAL_THREAD"})
    void update_deployedCallAndReply_propagatesReplacementsUnderExistingPolicies(
            ThreadingModel model, Vertx vertx, VertxTestContext test) {
        vertx.exceptionHandler(test::failNow);
        EventBusTransportHandler external = new EventBusTransportHandler(() -> List.of(new TenantContext.Codec()));
        vertx.eventBus().addInboundInterceptor(external::handleInbound);
        vertx.eventBus().addOutboundInterceptor(external::handleOutbound);
        FlowId original = FlowId.builder().withId("original").build();
        FlowId outgoing = FlowId.builder().withId("updated").build();
        DeploymentOptions options = new DeploymentOptions().setThreadingModel(model);
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                return vertx.eventBus().<String>consumer("atomic-update", message -> test.verify(() -> {
                    assertEquals("updated", FlowIdContext.require().getId());
                    assertNotSame(outgoing, FlowIdContext.require());
                    assertEquals("tenant-updated", TenantContext.VALUE.require().id());
                    FlowId remote = FlowIdContext.slot().update(current -> FlowId.builder()
                        .withId(current.getId() + "-remote").build());
                    TenantContext.Tenant tenant = TenantContext.VALUE.update(current ->
                        new TenantContext.Tenant(current.id() + "-remote"));
                    ContextInternal receiver = ContextInternal.current();
                    assertEquals(model, receiver.threadingModel());
                    vertx.timer(1).onComplete(test.succeeding(tick -> test.verify(() -> {
                        assertSame(receiver, ContextInternal.current());
                        assertSame(remote, FlowIdContext.require());
                        assertSame(tenant, TenantContext.VALUE.require());
                        message.reply("delivered");
                    })));
                })).completion();
            }
        }, options).compose(deployment -> vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                ContextInternal caller = ContextInternal.current();
                FlowIdContext.put(original);
                assertSame(outgoing, FlowIdContext.slot().update(current -> {
                    assertSame(original, current);
                    return outgoing;
                }));
                TenantContext.VALUE.put(new TenantContext.Tenant("tenant"));
                TenantContext.VALUE.update(current -> new TenantContext.Tenant(current.id() + "-updated"));
                return vertx.timer(1).compose(tick ->
                    vertx.eventBus().<String>request("atomic-update", "payload")).compose(reply -> {
                        assertEquals("delivered", reply.body());
                        assertSame(caller, ContextInternal.current());
                        assertSame(outgoing, FlowIdContext.require());
                        assertEquals("tenant-updated-remote", TenantContext.VALUE.require().id());
                        return vertx.timer(1);
                    }).map(tick -> {
                        assertSame(caller, ContextInternal.current());
                        assertSame(outgoing, FlowIdContext.require());
                        assertEquals("tenant-updated-remote", TenantContext.VALUE.require().id());
                        assertEquals("original", original.getId());
                        return tick;
                    });
            }
        }, options)).onComplete(test.succeedingThenComplete());
    }

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
