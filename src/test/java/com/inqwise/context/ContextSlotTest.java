package com.inqwise.context;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.inqwise.context.spi.ContextChainDataItem;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.vertx.core.spi.context.storage.AccessMode.CONCURRENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class ContextSlotTest {
    record Value(String text) implements ContextChainDataItem {
    }

    record Other(String text) implements ContextChainDataItem {
    }

    @Test
    void put_conflictingDeclaration_rejectsWithoutReplacingExistingItem(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextSlot<Value> owner = ContextSlots.define("shared", Value.class);
            ContextSlot<Other> conflicting = ContextSlots.define("shared", Other.class);
            Value original = new Value("original");
            owner.put(original);
            assertThrows(ClassCastException.class, () -> conflicting.put(new Other("conflicting")));
            assertSame(original, owner.require());
            Value replacement = new Value("replacement");
            owner.put(replacement);
            assertSame(replacement, owner.require());
            assertSame(replacement, owner.remove());
            owner.put(original);
            assertSame(original, owner.require());
            test.completeNow();
        }));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void define_invalidDeclarations_rejectsWithoutNeedingContext() {
        assertThrows(NullPointerException.class, () -> ContextSlots.define(null, Value.class));
        assertThrows(IllegalArgumentException.class, () -> ContextSlots.define(" \t", Value.class));
        assertThrows(NullPointerException.class, () -> ContextSlots.define("item", null));
        assertThrows(NullPointerException.class, () -> ContextSlots.define("item", Value.class, null));
        assertThrows(IllegalArgumentException.class, () -> ContextSlots.define("item", (Class) String.class));
        ContextSlot<Value> slot = ContextSlots.define("item", Value.class);
        assertEquals("item", slot.key());
        assertSame(Value.class, slot.type());
        var configuration = ContextTransportConfiguration.builder().include(slot).build();
        assertEquals(List.of("item"), new ArrayList<>(configuration.includedKeys()));
    }

    @Test
    void operations_noActiveContext_failWithoutInvokingFactory() {
        AtomicInteger calls = new AtomicInteger();
        ContextSlot<Value> slot = ContextSlots.define("item", Value.class, () -> {
            calls.incrementAndGet();
            return new Value("created");
        });
        assertThrows(IllegalStateException.class, slot::get);
        assertThrows(IllegalStateException.class, slot::require);
        assertThrows(IllegalStateException.class, () -> slot.put(new Value("value")));
        assertThrows(IllegalStateException.class, slot::remove);
        assertThrows(IllegalStateException.class, slot::getOrCreate);
        assertThrows(IllegalStateException.class, () -> slot.getOrCreate(() -> new Value("explicit")));
        assertEquals(0, calls.get());
    }

    @Test
    void get_absent_doesNotAllocateStorageOrCreateValue(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            AtomicInteger calls = new AtomicInteger();
            ContextSlot<Value> slot = ContextSlots.define("item", Value.class, () -> {
                calls.incrementAndGet();
                return new Value("created");
            });
            ContextInternal caller = ContextInternal.current();
            assertNull(caller.getLocal(ContextChainStorageHolder.STORAGE_CONTEXT, CONCURRENT));
            assertNull(slot.get());
            assertThrows(IllegalStateException.class, slot::require);
            assertNull(caller.getLocal(ContextChainStorageHolder.STORAGE_CONTEXT, CONCURRENT));
            assertEquals(0, calls.get());
            assertEquals("created", slot.getOrCreate().text());
            assertSame(slot.get(), slot.require());
            assertEquals(1, calls.get());
            test.completeNow();
        }));
    }

    @Test
    void getOrCreate_removalRequiresExplicitPut_andFailuresLeaveSlotAbsent(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextSlot<Value> slot = ContextSlots.define("item", Value.class);
            assertThrows(IllegalStateException.class, slot::getOrCreate);
            assertNull(slot.get());
            assertThrows(IllegalStateException.class,
                () -> slot.getOrCreate(() -> slot.getOrCreate(() -> new Value("recursive"))));
            assertNull(slot.get());
            assertThrows(NullPointerException.class, () -> slot.getOrCreate(null));
            assertThrows(NullPointerException.class, () -> slot.getOrCreate(() -> null));
            IllegalArgumentException original = new IllegalArgumentException("application factory failure");
            assertSame(original, assertThrows(IllegalArgumentException.class, () -> slot.getOrCreate(() -> { throw original; })));
            assertNull(slot.get());
            Value first = new Value("first");
            assertSame(first, slot.getOrCreate(() -> first));
            assertSame(first, slot.getOrCreate());
            assertSame(first, slot.getOrCreate(() -> { throw new AssertionError("Existing item must bypass creation"); }));
            assertSame(first, slot.remove());
            assertNull(slot.remove());
            assertThrows(IllegalStateException.class, slot::getOrCreate);
            assertThrows(IllegalStateException.class, () -> slot.getOrCreate(() -> { throw new AssertionError("Removed item must bypass creation"); }));
            Value replacement = new Value("replacement");
            slot.put(replacement);
            assertSame(replacement, slot.require());
            test.completeNow();
        }));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void operations_wrongTypes_rejectBeforeMutation(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextSlot<Value> slot = ContextSlots.define("item", Value.class);
            ContextSlot raw = slot;
            Other wrong = new Other("wrong");
            assertThrows(ClassCastException.class, () -> raw.put(wrong));
            assertThrows(ClassCastException.class, () -> raw.getOrCreate((Supplier) () -> wrong));
            assertNull(slot.get());
            assertThrows(NullPointerException.class, () -> slot.put(null));
            Storage.put("item", wrong);
            assertThrows(ClassCastException.class, slot::get);
            assertThrows(ClassCastException.class, slot::require);
            assertThrows(ClassCastException.class, slot::remove);
            assertThrows(ClassCastException.class, slot::getOrCreate);
            assertSame(wrong, Storage.get("item"));
            test.completeNow();
        }));
    }

    @Test
    void getOrCreate_concurrentAccess_createsExactlyOneStoredInstance(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> {
            ContextInternal caller = ContextInternal.current();
            var executor = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            List<CompletableFuture<ContextChainDataItem>> pending = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                pending.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        return DefaultStorage.getOrCreate(caller, "item", () -> {
                            calls.incrementAndGet();
                            return new Value("created");
                        });
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                }, executor));
            }
            start.countDown();
            ContextPropagation.bind(CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)))
                .andThen(result -> executor.shutdownNow()).onComplete(test.succeeding(done -> test.verify(() -> {
                    assertSame(caller, ContextInternal.current());
                    assertEquals(1, calls.get());
                    ContextChainDataItem first = pending.getFirst().join();
                    pending.forEach(future -> assertSame(first, future.join()));
                    assertSame(first, ContextSlots.define("item", Value.class).get());
                    assertTrue(executor.isShutdown());
                    assertFalse(DefaultStorage.isRemoved(first));
                    test.completeNow();
                })));
        });
    }
}
