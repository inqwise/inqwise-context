package com.inqwise.context;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import com.inqwise.context.spi.ContextChainDataItem;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class ContextSlotUpdateTest {
    record Counter(int value) implements ContextChainDataItem {
    }

    record Other(String value) implements ContextChainDataItem {
    }

    @Test
    void update_noActiveContext_rejectsWithoutInvokingUpdater() {
        AtomicInteger calls = new AtomicInteger();
        ContextSlot<Counter> slot = ContextSlots.define("counter", Counter.class);
        assertThrows(IllegalStateException.class, () -> slot.update(value -> {
            calls.incrementAndGet();
            return value;
        }));
        assertThrows(NullPointerException.class, () -> slot.update(null));
        assertEquals(0, calls.get());
    }

    @Test
    void update_existingItem_returnsExactReplacementAndAllowsIdentity(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextSlot<Counter> slot = ContextSlots.define("counter", Counter.class);
            Counter original = new Counter(1);
            Counter replacement = new Counter(2);
            Counter unrelated = new Counter(9);
            ContextSlot<Counter> other = ContextSlots.define("other", Counter.class);
            slot.put(original);
            other.put(unrelated);
            AtomicInteger calls = new AtomicInteger();
            assertSame(replacement, slot.update(value -> {
                calls.incrementAndGet();
                assertSame(original, value);
                return replacement;
            }));
            assertEquals(1, calls.get());
            assertSame(replacement, slot.require());
            assertSame(replacement, slot.update(UnaryOperator.identity()));
            assertSame(unrelated, other.require());
            assertEquals(1, original.value());
            test.completeNow();
        }));
    }

    @Test
    void update_absentOrRemoved_doesNotInvokeUpdaterOrFactory(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextSlot<Counter> slot = ContextSlots.define("counter", Counter.class,
                () -> { throw new AssertionError("Update must not create an item"); });
            UnaryOperator<Counter> forbidden = value -> {
                throw new AssertionError("Updater must not run without an existing item");
            };
            assertThrows(IllegalStateException.class, () -> slot.update(forbidden));
            assertNull(DefaultStorage.getEntry(ContextInternal.current(), slot.key()));
            assertNull(slot.remove());
            ContextChainDataItem marker = DefaultStorage.getEntry(ContextInternal.current(), slot.key());
            assertTrue(DefaultStorage.isRemoved(marker));
            assertThrows(IllegalStateException.class, () -> slot.update(forbidden));
            assertSame(marker, DefaultStorage.getEntry(ContextInternal.current(), slot.key()));
            assertThrows(IllegalStateException.class, () -> slot.getOrCreate(() -> new Counter(1)));
            slot.put(new Counter(1));
            assertEquals(2, slot.update(value -> new Counter(value.value() + 1)).value());
            test.completeNow();
        }));
    }

    @Test
    void update_throwingOrNullUpdater_retainsPreviousReference(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextSlot<Counter> slot = ContextSlots.define("counter", Counter.class);
            Counter original = new Counter(1);
            slot.put(original);
            IllegalArgumentException failure = new IllegalArgumentException("application failure");
            assertSame(failure, assertThrows(IllegalArgumentException.class,
                () -> slot.update(value -> { throw failure; })));
            assertSame(original, slot.require());
            assertThrows(NullPointerException.class, () -> slot.update(value -> null));
            assertThrows(NullPointerException.class, () -> slot.update(null));
            assertSame(original, slot.require());
            test.completeNow();
        }));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void update_incompatibleExistingOrResult_rejectsWithoutReplacement(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextSlot<Counter> slot = ContextSlots.define("counter", Counter.class);
            ContextSlot<Other> conflicting = ContextSlots.define("counter", Other.class);
            Counter original = new Counter(1);
            slot.put(original);
            assertThrows(ClassCastException.class, () -> conflicting.update(value -> {
                throw new AssertionError("Wrong-type input must not reach updater");
            }));
            assertSame(original, slot.require());
            ContextSlot raw = slot;
            assertThrows(ClassCastException.class, () -> raw.update(value -> new Other("wrong")));
            assertSame(original, slot.require());
            test.completeNow();
        }));
    }

    @Test
    void update_concurrentReplacements_doesNotLoseChanges(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> {
            ContextInternal caller = ContextInternal.current();
            ContextSlot<Counter> slot = ContextSlots.define("counter", Counter.class);
            slot.put(new Counter(0));
            var executor = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            List<CompletableFuture<Void>> pending = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                pending.add(CompletableFuture.runAsync(() -> {
                    try {
                        start.await();
                        for (int iteration = 0; iteration < 100; iteration++) {
                            DefaultStorage.update(caller, slot.key(), slot.type(), value -> {
                                calls.incrementAndGet();
                                return new Counter(value.value() + 1);
                            });
                        }
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
                    assertEquals(800, calls.get());
                    assertEquals(800, slot.require().value());
                    assertTrue(executor.isShutdown());
                    test.completeNow();
                })));
        });
    }
}
