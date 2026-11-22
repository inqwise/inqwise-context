package com.inqwise.context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class ContextListTest {
    record Value(String text) {
    }

    @Test
    void declaration_invalidInputs_rejectsWithoutAContext() {
        assertThrows(NullPointerException.class, () -> ContextLists.define(null, String.class));
        assertThrows(IllegalArgumentException.class, () -> ContextLists.define(" \t", String.class));
        assertThrows(NullPointerException.class, () -> ContextLists.define("list", null));
        assertThrows(IllegalArgumentException.class, () -> ContextLists.define("list", int.class));
    }

    @Test
    void item_mutableInputAndViews_preservesOrderDuplicatesAndReferences() {
        ContextList<Value> list = ContextLists.define("list", Value.class);
        Value first = new Value("same");
        Value duplicate = new Value("same");
        Value middle = new Value("middle");
        List<Value> input = new ArrayList<>(List.of(first, middle, duplicate));
        ContextListItem<Value> item = list.item(input);
        input.clear();
        assertEquals(List.of(first, middle, duplicate), item.snapshot());
        assertSame(first, item.snapshot().get(0));
        assertSame(duplicate, item.snapshot().get(2));
        assertSame(Value.class, item.elementType());
        assertSame(item, list.cast(item));
        assertSame(item.snapshot(), item.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> item.snapshot().set(0, middle));
        assertThrows(UnsupportedOperationException.class, () -> item.snapshot().subList(0, 1).clear());
        var iterator = item.snapshot().listIterator();
        iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertThrows(UnsupportedOperationException.class, () -> iterator.set(middle));
        assertThrows(UnsupportedOperationException.class, () -> iterator.add(middle));
        assertThrows(NullPointerException.class, () -> list.item(null));
        assertThrows(NullPointerException.class, () -> list.item(java.util.Arrays.asList(first, null)));
        assertThrows(NullPointerException.class, () -> list.cast(null));
        assertThrows(ClassCastException.class, () -> list.cast(ContextLists.define("list", String.class).item(List.of())));
        assertThrows(ClassCastException.class, () -> list.cast(ContextSets.define("list", Value.class).item(Set.of())));
    }

    @ParameterizedTest
    @EnumSource(value = ThreadingModel.class, names = {"EVENT_LOOP", "WORKER", "VIRTUAL_THREAD"})
    void operations_deployedFlow_retainsOrderAndDistinguishesWholeItemRemoval(
            ThreadingModel model, Vertx vertx, VertxTestContext test) {
        ContextList<String> list = ContextLists.define("list", String.class);
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                FlowId identity = FlowId.builder().withId("canonical").build();
                FlowIdContext.put(identity);
                assertThrows(IllegalStateException.class, list::snapshot);
                assertThrows(IllegalStateException.class, () -> list.get(0));
                assertThrows(IllegalStateException.class, () -> list.append("missing"));
                assertThrows(IllegalStateException.class, list::clear);
                List<String> empty = list.getOrCreate();
                assertSame(empty, list.getOrCreate(() -> { throw new AssertionError("Existing list invoked factory"); }));
                assertEquals(0, list.append("first"));
                assertEquals(1, list.append("first"));
                List<String> before = list.snapshot();
                list.insert(1, "middle");
                assertEquals(List.of("first", "middle", "first"), list.snapshot());
                assertEquals("middle", list.get(1));
                assertEquals("middle", list.set(1, "changed"));
                assertEquals("changed", list.removeAt(1));
                list.appendAll(List.of("last", "last"));
                list.insertAll(2, List.of("inserted", "inserted"));
                assertEquals(List.of("first", "first", "inserted", "inserted", "last", "last"), list.snapshot());
                assertEquals(List.of("first", "first"), before);
                list.clear();
                assertNotNull(list.slot().get());
                list.insert(0, "only");
                assertEquals("only", list.removeAt(0));
                assertNotNull(list.slot().get());
                assertTrue(list.remove().isEmpty());
                assertNull(list.remove());
                assertThrows(IllegalStateException.class, list::snapshot);
                assertThrows(IllegalStateException.class, list::getOrCreate);
                assertThrows(IllegalStateException.class, () -> list.getOrCreate(() -> { throw new AssertionError("Revival"); }));
                assertThrows(IllegalStateException.class, () -> list.append("revival"));
                assertThrows(IllegalStateException.class, () -> list.insert(0, "revival"));
                assertThrows(IllegalStateException.class, () -> list.set(0, "revival"));
                assertThrows(IllegalStateException.class, () -> list.removeAt(0));
                assertThrows(IllegalStateException.class, () -> list.appendAll(List.of()));
                assertThrows(IllegalStateException.class, () -> list.insertAll(0, List.of()));
                assertThrows(IllegalStateException.class, list::clear);
                assertThrows(IllegalStateException.class, () -> list.update(current -> { throw new AssertionError("Revival"); }));
                list.replace(List.of("restored", "restored"));
                List<String> restored = list.snapshot();
                return vertx.timer(1).map(tick -> {
                    assertEquals(List.of("restored", "restored"), restored);
                    assertSame(restored, list.snapshot());
                    assertSame(identity, FlowIdContext.require());
                    assertEquals(model, ContextInternal.current().threadingModel());
                    assertTrue(empty.isEmpty());
                    return tick;
                });
            }
        }, new DeploymentOptions().setThreadingModel(model)).onComplete(test.succeedingThenComplete());
    }

    @Test
    void indices_outOfBounds_rejectWithoutReplacingSnapshot(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextList<String> list = ContextLists.define("list", String.class);
            list.replace(List.of("original"));
            List<String> before = list.snapshot();
            for (int index : new int[] {-1, 1, 2}) {
                assertThrows(IndexOutOfBoundsException.class, () -> list.get(index));
                assertThrows(IndexOutOfBoundsException.class, () -> list.set(index, "changed"));
                assertThrows(IndexOutOfBoundsException.class, () -> list.removeAt(index));
            }
            for (int index : new int[] {-1, 2}) {
                assertThrows(IndexOutOfBoundsException.class, () -> list.insert(index, "changed"));
                assertThrows(IndexOutOfBoundsException.class, () -> list.insertAll(index, List.of("changed")));
                assertThrows(IndexOutOfBoundsException.class, () -> list.insertAll(index, List.of()));
            }
            assertSame(before, list.snapshot());
            list.insert(1, "end");
            list.insertAll(2, List.of("batch"));
            assertEquals(List.of("original", "end", "batch"), list.snapshot());
            test.completeNow();
        }));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void mutation_invalidElementOrBatch_cannotApplyPartialChanges(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextList<String> list = ContextLists.define("list", String.class);
            ContextList raw = list;
            list.replace(List.of("original"));
            List<String> before = list.snapshot();
            assertThrows(NullPointerException.class, () -> list.append(null));
            assertThrows(NullPointerException.class, () -> list.insert(0, null));
            assertThrows(NullPointerException.class, () -> list.set(0, null));
            assertThrows(NullPointerException.class, () -> list.appendAll(null));
            assertThrows(ClassCastException.class, () -> raw.append(42));
            assertThrows(ClassCastException.class, () -> raw.insert(0, 42));
            assertThrows(ClassCastException.class, () -> raw.set(0, 42));
            List invalid = new ArrayList(List.of("valid", 42));
            assertThrows(ClassCastException.class, () -> raw.appendAll(invalid));
            assertThrows(ClassCastException.class, () -> raw.insertAll(0, invalid));
            assertThrows(ClassCastException.class, () -> raw.replace(invalid));
            assertThrows(ClassCastException.class, () -> raw.update(current -> new ArrayList(invalid)));
            invalid.set(1, null);
            assertThrows(NullPointerException.class, () -> raw.appendAll(invalid));
            assertSame(before, list.snapshot());
            test.completeNow();
        }));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void declarations_conflictingTypesAndFamilies_rejectEmptyValues(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextInternal caller = ContextInternal.current();
            ContextList<String> owner = ContextLists.define("list", String.class);
            ContextList<Long> wrong = ContextLists.define("list", Long.class);
            owner.replace(List.of());
            ContextListItem<String> original = owner.slot().require();
            assertSame(original.snapshot(), ContextLists.define("list", String.class).snapshot());
            assertThrows(ClassCastException.class, wrong::snapshot);
            assertThrows(ClassCastException.class, wrong::getOrCreate);
            assertThrows(ClassCastException.class, () -> wrong.replace(List.of()));
            assertThrows(ClassCastException.class, wrong::remove);
            assertThrows(ClassCastException.class, wrong::clear);
            ContextSlot raw = owner.slot();
            assertThrows(ClassCastException.class, () -> raw.put(wrong.item(List.of())));
            assertThrows(ClassCastException.class, () -> raw.update(current -> wrong.item(List.of())));
            ContextSlot erased = ContextSlots.define("list", (Class) ContextListItem.class);
            assertThrows(ClassCastException.class, () -> erased.put(wrong.item(List.of())));
            assertThrows(ClassCastException.class, () -> erased.update(current -> wrong.item(List.of())));
            ContextChainDataItem[] conflicts = {wrong.item(List.of()), FlowId.builder().withId("other").build(),
                ContextMaps.define("list", String.class, String.class).item(Map.of()),
                ContextSets.define("list", String.class).item(Set.of())};
            for (ContextChainDataItem incoming : conflicts) {
                assertThrows(ClassCastException.class, () -> DefaultStorage.put(caller, "list", incoming, true));
                DefaultStorage.put(caller, "list", incoming, false);
                assertSame(original, owner.slot().require());
            }
            FlowId plain = FlowId.builder().withId("plain").build();
            DefaultStorage.put(caller, "plain", plain, true);
            assertThrows(ClassCastException.class, () -> DefaultStorage.put(caller, "plain", original, true));
            assertSame(plain, DefaultStorage.get(caller, "plain"));
            DefaultStorage.remove(caller, "list", true);
            DefaultStorage.put(caller, "list", wrong.item(List.of(1L)), true);
            assertEquals(List.of(1L), wrong.snapshot());
            test.completeNow();
        }));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void creationAndUpdate_failuresAndMutableResults_retainPriorSnapshots(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextList<String> list = ContextLists.define("list", String.class);
            ContextSlot raw = list.slot();
            assertThrows(ClassCastException.class, () -> raw.getOrCreate(() -> ContextLists.define("list", Long.class).item(List.of())));
            assertNull(list.slot().get());
            List<String> input = new ArrayList<>(List.of("created", "created"));
            List<String> created = list.getOrCreate(() -> input);
            input.clear();
            assertEquals(List.of("created", "created"), created);
            IllegalArgumentException failure = new IllegalArgumentException("application updater failure");
            assertSame(failure, assertThrows(IllegalArgumentException.class,
                () -> list.update(current -> { throw failure; })));
            assertThrows(NullPointerException.class, () -> list.update(null));
            assertThrows(NullPointerException.class, () -> list.update(current -> null));
            assertThrows(UnsupportedOperationException.class, () -> list.update(current -> {
                current.set(0, "mutation");
                return current;
            }));
            assertSame(created, list.snapshot());
            List<String> result = new ArrayList<>(List.of("changed", "changed"));
            List<String> stored = list.update(current -> result);
            result.clear();
            assertEquals(List.of("changed", "changed"), stored);
            assertSame(stored, list.snapshot());
            assertEquals(List.of("created", "created"), created);
            test.completeNow();
        }));
    }

    @Test
    void operations_noActiveContext_failWithoutInvokingCallbacks() {
        ContextList<String> list = ContextLists.define("list", String.class);
        assertThrows(IllegalStateException.class, list::snapshot);
        assertThrows(IllegalStateException.class, list::getOrCreate);
        assertThrows(IllegalStateException.class, () -> list.getOrCreate(() -> { throw new AssertionError("Factory"); }));
        assertThrows(NullPointerException.class, () -> list.getOrCreate(null));
        assertThrows(IllegalStateException.class, () -> list.get(0));
        assertThrows(IllegalStateException.class, () -> list.append("value"));
        assertThrows(IllegalStateException.class, () -> list.insert(0, "value"));
        assertThrows(IllegalStateException.class, () -> list.set(0, "value"));
        assertThrows(IllegalStateException.class, () -> list.removeAt(0));
        assertThrows(IllegalStateException.class, () -> list.appendAll(List.of()));
        assertThrows(IllegalStateException.class, () -> list.insertAll(0, List.of()));
        assertThrows(IllegalStateException.class, list::remove);
        assertThrows(IllegalStateException.class, list::clear);
        assertThrows(IllegalStateException.class, () -> list.replace(List.of()));
        assertThrows(IllegalStateException.class, () -> list.update(current -> { throw new AssertionError("Updater"); }));
    }

    @Test
    void append_concurrentCalls_keepEveryDuplicateAndAssignDistinctIndices(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> {
            ContextList<String> list = ContextLists.define("list", String.class);
            List<String> initial = list.getOrCreate();
            ContextInternal caller = ContextInternal.current();
            CountDownLatch start = new CountDownLatch(1);
            ConcurrentLinkedQueue<Integer> positions = new ConcurrentLinkedQueue<>();
            List<Future<Void>> pending = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                int id = worker;
                pending.add(caller.executeBlocking(() -> {
                    start.await();
                    assertSame(caller, ContextInternal.current());
                    positions.add(list.append("shared"));
                    for (int value = 0; value < 32; value++) {
                        positions.add(list.append(id + ":" + value));
                    }
                    return null;
                }, false));
            }
            start.countDown();
            Future.join(pending).onComplete(test.succeeding(done -> test.verify(() -> {
                List<String> snapshot = list.snapshot();
                assertEquals(264, snapshot.size());
                assertEquals(8L, snapshot.stream().filter("shared"::equals).count());
                assertEquals(257, new HashSet<>(snapshot).size());
                assertEquals(264, new HashSet<>(positions).size());
                for (int index = 0; index < 264; index++) {
                    assertTrue(positions.contains(index));
                }
                for (int id = 0; id < 8; id++) {
                    int previous = -1;
                    for (int value = 0; value < 32; value++) {
                        int index = snapshot.indexOf(id + ":" + value);
                        assertTrue(index > previous);
                        previous = index;
                    }
                }
                assertTrue(initial.isEmpty());
                test.completeNow();
            })));
        });
    }
}
