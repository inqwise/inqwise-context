package com.inqwise.context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.spi.ContextChainDataItem;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class ContextSetTest {
    record Value(String text) {
    }

    @Test
    void declaration_invalidInputs_rejectsWithoutAContext() {
        assertThrows(NullPointerException.class, () -> ContextSets.define(null, String.class));
        assertThrows(IllegalArgumentException.class, () -> ContextSets.define(" \t", String.class));
        assertThrows(NullPointerException.class, () -> ContextSets.define("set", null));
        assertThrows(IllegalArgumentException.class, () -> ContextSets.define("set", int.class));
    }

    @Test
    void item_inputAndIterator_cannotMutateSnapshot() {
        ContextSet<Value> set = ContextSets.define("set", Value.class);
        Value first = new Value("first");
        Value second = new Value("second");
        List<Value> input = new ArrayList<>(List.of(first, second, new Value("first")));
        ContextSetItem<Value> item = set.item(input);
        input.clear();
        assertEquals(List.of(first, second), new ArrayList<>(item.snapshot()));
        assertSame(first, item.snapshot().iterator().next());
        assertSame(Value.class, item.elementType());
        assertSame(item, set.cast(item));
        assertSame(item.snapshot(), item.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> item.snapshot().add(first));
        assertThrows(UnsupportedOperationException.class, () -> item.snapshot().clear());
        var iterator = item.snapshot().iterator();
        iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertThrows(NullPointerException.class, () -> set.item(null));
        assertThrows(NullPointerException.class, () -> set.item(java.util.Arrays.asList(first, null)));
        assertThrows(NullPointerException.class, () -> set.cast(null));
        assertThrows(ClassCastException.class, () -> set.cast(ContextSets.define("set", String.class).item(Set.of())));
        assertThrows(ClassCastException.class, () -> set.cast(ContextMaps.define("set", String.class, Value.class).item(Map.of())));
    }

    @ParameterizedTest
    @EnumSource(value = ThreadingModel.class, names = {"EVENT_LOOP", "WORKER", "VIRTUAL_THREAD"})
    void membership_deployedFlow_keepsSetAndWholeItemLifecyclesSeparate(
            ThreadingModel model, Vertx vertx, VertxTestContext test) {
        ContextSet<String> set = ContextSets.define("set", String.class);
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                FlowId identity = FlowId.builder().withId("canonical").build();
                FlowIdContext.put(identity);
                assertThrows(IllegalStateException.class, set::snapshot);
                assertThrows(IllegalStateException.class, () -> set.contains("missing"));
                assertThrows(IllegalStateException.class, () -> set.add("missing"));
                assertThrows(IllegalStateException.class, set::clear);
                Set<String> empty = set.getOrCreate();
                assertSame(empty, set.getOrCreate(() -> { throw new AssertionError("Existing set invoked factory"); }));
                assertTrue(set.add("first"));
                assertFalse(set.add("first"));
                assertTrue(set.contains("first"));
                assertFalse(set.contains("missing"));
                Set<String> first = set.snapshot();
                assertTrue(set.addAll(List.of("first", "second", "second", "third")));
                assertFalse(set.addAll(Set.of("first")));
                assertEquals(Set.of("first"), first);
                assertTrue(set.remove("second"));
                assertFalse(set.remove("missing"));
                assertTrue(set.removeAll(Set.of("third", "missing")));
                assertFalse(set.removeAll(Set.of("missing")));
                assertTrue(set.addAll(Set.of("second", "third")));
                assertTrue(set.retainAll(Set.of("first", "second")));
                assertFalse(set.retainAll(Set.of("first", "second")));
                set.clear();
                assertTrue(set.snapshot().isEmpty());
                assertNotNull(set.slot().get());
                set.add("last");
                assertTrue(set.remove("last"));
                assertNotNull(set.slot().get());
                assertTrue(set.remove().isEmpty());
                assertNull(set.remove());
                assertThrows(IllegalStateException.class, set::snapshot);
                assertThrows(IllegalStateException.class, set::getOrCreate);
                assertThrows(IllegalStateException.class, () -> set.getOrCreate(() -> { throw new AssertionError("Revival"); }));
                assertThrows(IllegalStateException.class, () -> set.add("revival"));
                assertThrows(IllegalStateException.class, () -> set.remove("revival"));
                assertThrows(IllegalStateException.class, () -> set.addAll(Set.of()));
                assertThrows(IllegalStateException.class, () -> set.removeAll(Set.of()));
                assertThrows(IllegalStateException.class, () -> set.retainAll(Set.of()));
                assertThrows(IllegalStateException.class, set::clear);
                assertThrows(IllegalStateException.class, () -> set.update(current -> { throw new AssertionError("Revival"); }));
                set.replace(List.of("restored", "restored"));
                Set<String> restored = set.snapshot();
                return vertx.timer(1).map(tick -> {
                    assertEquals(Set.of("restored"), restored);
                    assertSame(restored, set.snapshot());
                    assertSame(identity, FlowIdContext.require());
                    assertEquals(model, ContextInternal.current().threadingModel());
                    assertTrue(empty.isEmpty());
                    return tick;
                });
            }
        }, new DeploymentOptions().setThreadingModel(model)).onComplete(test.succeedingThenComplete());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void mutation_invalidBatchOrRawElement_retainsPriorReference(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextSet<String> set = ContextSets.define("set", String.class);
            ContextSet raw = set;
            set.replace(Set.of("original"));
            Set<String> before = set.snapshot();
            assertThrows(NullPointerException.class, () -> set.add(null));
            assertThrows(NullPointerException.class, () -> set.remove(null));
            assertThrows(NullPointerException.class, () -> set.contains(null));
            assertThrows(NullPointerException.class, () -> set.addAll(null));
            assertThrows(ClassCastException.class, () -> raw.add(42));
            assertThrows(ClassCastException.class, () -> raw.contains(42));
            assertThrows(ClassCastException.class, () -> raw.remove(42));
            List invalid = new ArrayList(List.of("valid", 42));
            assertThrows(ClassCastException.class, () -> raw.addAll(invalid));
            assertThrows(ClassCastException.class, () -> raw.removeAll(invalid));
            assertThrows(ClassCastException.class, () -> raw.retainAll(invalid));
            assertThrows(ClassCastException.class, () -> raw.replace(invalid));
            assertThrows(ClassCastException.class, () -> raw.update(current -> new HashSet(invalid)));
            invalid.set(1, null);
            assertThrows(NullPointerException.class, () -> raw.addAll(invalid));
            assertSame(before, set.snapshot());
            test.completeNow();
        }));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void declarations_conflictingTypesAndContainerFamilies_rejectEmptyValues(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextInternal caller = ContextInternal.current();
            ContextSet<String> owner = ContextSets.define("set", String.class);
            ContextSet<Long> wrong = ContextSets.define("set", Long.class);
            owner.replace(Set.of());
            ContextSetItem<String> original = owner.slot().require();
            assertSame(original.snapshot(), ContextSets.define("set", String.class).snapshot());
            assertThrows(ClassCastException.class, wrong::snapshot);
            assertThrows(ClassCastException.class, wrong::getOrCreate);
            assertThrows(ClassCastException.class, () -> wrong.replace(Set.of()));
            assertThrows(ClassCastException.class, wrong::remove);
            assertThrows(ClassCastException.class, wrong::clear);
            ContextSlot raw = owner.slot();
            assertThrows(ClassCastException.class, () -> raw.put(wrong.item(Set.of())));
            assertThrows(ClassCastException.class, () -> raw.update(current -> wrong.item(Set.of())));
            ContextChainDataItem[] conflicts = {wrong.item(Set.of()), FlowId.builder().withId("other").build(),
                ContextMaps.define("set", String.class, String.class).item(Map.of())};
            ContextSlot erased = ContextSlots.define("set", (Class) ContextSetItem.class);
            assertThrows(ClassCastException.class, () -> erased.put(wrong.item(Set.of())));
            assertThrows(ClassCastException.class, () -> erased.update(current -> wrong.item(Set.of())));
            for (ContextChainDataItem incoming : conflicts) {
                assertThrows(ClassCastException.class, () -> DefaultStorage.put(caller, "set", incoming, true));
                DefaultStorage.put(caller, "set", incoming, false);
                assertSame(original, owner.slot().require());
            }
            ContextMap<String, String> map = ContextMaps.define("map", String.class, String.class);
            map.replace(Map.of());
            assertThrows(ClassCastException.class, () -> DefaultStorage.put(caller, "map", original, true));
            assertTrue(map.snapshot().isEmpty());
            DefaultStorage.remove(caller, "set", true);
            DefaultStorage.put(caller, "set", wrong.item(Set.of(1L)), true);
            assertEquals(Set.of(1L), wrong.snapshot());
            FlowId plain = FlowId.builder().withId("plain").build();
            DefaultStorage.put(caller, "plain", plain, true);
            assertThrows(ClassCastException.class, () -> DefaultStorage.put(caller, "plain", original, true));
            assertSame(plain, DefaultStorage.get(caller, "plain"));
            test.completeNow();
        }));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void creationAndUpdate_failedOrMutableResults_doNotLeakMembershipChanges(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextSet<String> set = ContextSets.define("set", String.class);
            ContextSlot raw = set.slot();
            assertThrows(ClassCastException.class, () -> raw.getOrCreate(() -> ContextSets.define("set", Long.class).item(Set.of())));
            assertNull(set.slot().get());
            Set<String> input = new HashSet<>(Set.of("created"));
            Set<String> created = set.getOrCreate(() -> input);
            input.clear();
            assertEquals(Set.of("created"), created);
            IllegalArgumentException failure = new IllegalArgumentException("application updater failure");
            assertSame(failure, assertThrows(IllegalArgumentException.class,
                () -> set.update(current -> { throw failure; })));
            assertThrows(NullPointerException.class, () -> set.update(null));
            assertThrows(NullPointerException.class, () -> set.update(current -> null));
            assertThrows(UnsupportedOperationException.class, () -> set.update(current -> {
                current.add("mutation");
                return current;
            }));
            assertSame(created, set.snapshot());
            Set<String> result = new LinkedHashSet<>(Set.of("changed"));
            Set<String> stored = set.update(current -> result);
            result.clear();
            assertEquals(Set.of("changed"), stored);
            assertSame(stored, set.snapshot());
            assertEquals(Set.of("created"), created);
            test.completeNow();
        }));
    }

    @Test
    void operations_noActiveContext_failWithoutInvokingCallbacks() {
        ContextSet<String> set = ContextSets.define("set", String.class);
        assertThrows(IllegalStateException.class, set::snapshot);
        assertThrows(IllegalStateException.class, set::getOrCreate);
        assertThrows(IllegalStateException.class, () -> set.getOrCreate(() -> { throw new AssertionError("Factory"); }));
        assertThrows(NullPointerException.class, () -> set.getOrCreate(null));
        assertThrows(IllegalStateException.class, () -> set.contains("value"));
        assertThrows(IllegalStateException.class, () -> set.add("value"));
        assertThrows(IllegalStateException.class, () -> set.remove("value"));
        assertThrows(IllegalStateException.class, () -> set.addAll(List.of()));
        assertThrows(IllegalStateException.class, () -> set.removeAll(List.of()));
        assertThrows(IllegalStateException.class, () -> set.retainAll(List.of()));
        assertThrows(IllegalStateException.class, set::remove);
        assertThrows(IllegalStateException.class, set::clear);
        assertThrows(IllegalStateException.class, () -> set.replace(Set.of()));
        assertThrows(IllegalStateException.class, () -> set.update(current -> { throw new AssertionError("Updater"); }));
    }

    @Test
    void add_concurrentMembership_keepsUniqueElementsAndReportsOneDuplicateWinner(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> {
            ContextSet<String> set = ContextSets.define("set", String.class);
            Set<String> initial = set.getOrCreate();
            ContextInternal caller = ContextInternal.current();
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger insertedShared = new AtomicInteger();
            List<Future<Void>> pending = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                int id = worker;
                pending.add(caller.executeBlocking(() -> {
                    start.await();
                    assertSame(caller, ContextInternal.current());
                    if (set.add("shared")) {
                        insertedShared.incrementAndGet();
                    }
                    for (int value = 0; value < 32; value++) {
                        assertTrue(set.add(id + ":" + value));
                    }
                    return null;
                }, false));
            }
            start.countDown();
            Future.join(pending).onComplete(test.succeeding(done -> test.verify(() -> {
                assertEquals(1, insertedShared.get());
                assertEquals(257, set.snapshot().size());
                for (int id = 0; id < 8; id++) {
                    for (int value = 0; value < 32; value++) {
                        assertTrue(set.contains(id + ":" + value));
                    }
                }
                assertTrue(initial.isEmpty());
                test.completeNow();
            })));
        });
    }
}
