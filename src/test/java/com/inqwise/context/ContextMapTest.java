package com.inqwise.context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import com.inqwise.context.data.FlowId;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class ContextMapTest {
    record Value(String text) {
    }

    @Test
    void declaration_invalidArguments_rejectsWithoutContext() {
        assertThrows(NullPointerException.class, () -> ContextMaps.define(null, String.class, Value.class));
        assertThrows(IllegalArgumentException.class, () -> ContextMaps.define(" \t", String.class, Value.class));
        assertThrows(NullPointerException.class, () -> ContextMaps.define("map", null, Value.class));
        assertThrows(NullPointerException.class, () -> ContextMaps.define("map", String.class, null));
        assertThrows(IllegalArgumentException.class, () -> ContextMaps.define("map", int.class, Value.class));
        assertThrows(IllegalArgumentException.class, () -> ContextMaps.define("map", String.class, int.class));
    }

    @Test
    void item_inputAndViews_cannotMutateSnapshotOrEraseParameterTypes() {
        ContextMap<String, Value> map = ContextMaps.define("map", String.class, Value.class);
        Value value = new Value("retained");
        Map<String, Value> input = new LinkedHashMap<>();
        input.put("first", value);
        input.put("second", value);
        ContextMapItem<String, Value> item = map.item(input);
        input.clear();
        assertEquals(List.of("first", "second"), new ArrayList<>(item.snapshot().keySet()));
        assertSame(value, item.snapshot().get("first"));
        assertSame(item.snapshot(), item.snapshot());
        assertSame(String.class, item.keyType());
        assertSame(Value.class, item.valueType());
        assertSame(item, map.cast(item));
        assertThrows(UnsupportedOperationException.class, () -> item.snapshot().put("third", value));
        assertThrows(UnsupportedOperationException.class, () -> item.snapshot().entrySet().iterator().next().setValue(value));
        assertThrows(UnsupportedOperationException.class, () -> item.snapshot().values().clear());
        assertThrows(UnsupportedOperationException.class, () -> item.snapshot().keySet().remove("first"));
        assertThrows(ClassCastException.class, () -> map.cast(ContextMaps.define("map", String.class, Integer.class).item(Map.of())));
        assertThrows(ClassCastException.class, () -> map.cast(ContextMaps.define("map", Integer.class, Value.class).item(Map.of())));
        assertThrows(ClassCastException.class, () -> map.cast(FlowId.builder().withId("wrong").build()));
        assertThrows(NullPointerException.class, () -> map.cast(null));
        assertThrows(NullPointerException.class, () -> map.item(null));
    }

    @ParameterizedTest
    @EnumSource(value = ThreadingModel.class, names = {"EVENT_LOOP", "WORKER", "VIRTUAL_THREAD"})
    void operations_deployedFlow_distinguishEntriesEmptyMapsAndWholeRemoval(
            ThreadingModel model, Vertx vertx, VertxTestContext test) {
        ContextMap<String, Value> map = ContextMaps.define("map", String.class, Value.class);
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                FlowId identity = FlowId.builder().withId("canonical").build();
                FlowIdContext.put(identity);
                assertThrows(IllegalStateException.class, map::snapshot);
                assertThrows(IllegalStateException.class, () -> map.get("missing"));
                assertThrows(IllegalStateException.class, () -> map.put("missing", new Value("value")));
                assertThrows(IllegalStateException.class, map::clear);
                Map<String, Value> empty = map.getOrCreate();
                assertSame(empty, map.getOrCreate(() -> { throw new AssertionError("Existing map invoked factory"); }));
                assertTrue(empty.isEmpty());
                Value first = new Value("first");
                Value second = new Value("second");
                assertNull(map.put("entry", first));
                assertSame(first, map.get("entry"));
                assertNull(map.get("missing"));
                assertSame(first, map.putIfAbsent("entry", second));
                assertSame(first, map.put("entry", second));
                assertNull(map.putIfAbsent("other", first));
                Map<String, Value> beforeBatch = map.snapshot();
                map.putAll(Map.of("entry", first, "batch", second));
                assertEquals(Map.of("entry", second, "other", first), beforeBatch);
                assertSame(first, map.get("entry"));
                assertSame(second, map.remove("batch"));
                assertNull(map.remove("missing"));
                map.clear();
                assertTrue(map.snapshot().isEmpty());
                assertNotNull(map.slot().get());
                map.put("last", first);
                assertSame(first, map.remove("last"));
                assertNotNull(map.slot().get());
                Map<String, Value> removed = map.remove();
                assertTrue(removed.isEmpty());
                assertNull(map.remove());
                assertThrows(IllegalStateException.class, map::snapshot);
                assertThrows(IllegalStateException.class, map::getOrCreate);
                assertThrows(IllegalStateException.class, () -> map.getOrCreate(() -> { throw new AssertionError("Revival"); }));
                assertThrows(IllegalStateException.class, () -> map.put("entry", first));
                assertThrows(IllegalStateException.class, () -> map.putIfAbsent("entry", first));
                assertThrows(IllegalStateException.class, () -> map.remove("entry"));
                assertThrows(IllegalStateException.class, () -> map.putAll(Map.of()));
                assertThrows(IllegalStateException.class, map::clear);
                assertThrows(IllegalStateException.class, () -> map.update(current -> { throw new AssertionError("Revival"); }));
                map.replace(Map.of("restored", first));
                Map<String, Value> restored = map.snapshot();
                return vertx.timer(1).map(tick -> {
                    assertSame(restored, map.snapshot());
                    assertSame(identity, FlowIdContext.require());
                    assertEquals(model, ContextInternal.current().threadingModel());
                    return tick;
                });
            }
        }, new DeploymentOptions().setThreadingModel(model)).onComplete(test.succeedingThenComplete());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void operations_invalidElementsOrBatch_rejectsWithoutPartialMutation(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextMap<String, Value> map = ContextMaps.define("map", String.class, Value.class);
            ContextMap raw = map;
            Value original = new Value("original");
            map.replace(Map.of("original", original));
            Map<String, Value> before = map.snapshot();
            assertThrows(NullPointerException.class, () -> map.put(null, original));
            assertThrows(NullPointerException.class, () -> map.put("null", null));
            assertThrows(NullPointerException.class, () -> map.putIfAbsent("null", null));
            assertThrows(NullPointerException.class, () -> map.get(null));
            assertThrows(NullPointerException.class, () -> map.remove(null));
            assertThrows(ClassCastException.class, () -> raw.put(42, original));
            assertThrows(ClassCastException.class, () -> raw.get(42));
            assertThrows(ClassCastException.class, () -> raw.remove(42));
            assertThrows(ClassCastException.class, () -> raw.put("wrong", 42));
            Map invalid = new LinkedHashMap();
            invalid.put("valid", original);
            invalid.put("wrong", 42);
            assertThrows(ClassCastException.class, () -> raw.putAll(invalid));
            assertThrows(ClassCastException.class, () -> raw.replace(invalid));
            invalid.put("wrong", null);
            assertThrows(NullPointerException.class, () -> raw.putAll(invalid));
            assertSame(before, map.snapshot());
            test.completeNow();
        }));
    }

    @Test
    void update_failuresAndMutableResult_retainPriorSnapshots(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextMap<String, Integer> map = ContextMaps.define("map", String.class, Integer.class);
            map.replace(Map.of("initial", 1));
            Map<String, Integer> before = map.snapshot();
            IllegalArgumentException failure = new IllegalArgumentException("application updater failure");
            assertSame(failure, assertThrows(IllegalArgumentException.class,
                () -> map.update(current -> { throw failure; })));
            assertThrows(NullPointerException.class, () -> map.update(null));
            assertThrows(NullPointerException.class, () -> map.update(current -> null));
            assertThrows(UnsupportedOperationException.class, () -> map.update(current -> {
                current.put("mutation", 2);
                return current;
            }));
            assertSame(before, map.snapshot());
            Map<String, Integer> replacement = new HashMap<>(Map.of("changed", 2));
            Map<String, Integer> stored = map.update(current -> replacement);
            replacement.clear();
            assertEquals(Map.of("changed", 2), stored);
            assertSame(stored, map.snapshot());
            assertEquals(Map.of("initial", 1), before);
            test.completeNow();
        }));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void declarations_conflictingParameters_rejectEvenEmptyMapsAndRawSlotWrites(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextMap<String, Integer> owner = ContextMaps.define("map", String.class, Integer.class);
            ContextMap<String, Long> wrongValue = ContextMaps.define("map", String.class, Long.class);
            ContextMap<Integer, Integer> wrongKey = ContextMaps.define("map", Integer.class, Integer.class);
            ContextMapItem<String, Integer> original = owner.item(Map.of());
            owner.slot().put(original);
            assertSame(original.snapshot(), ContextMaps.define("map", String.class, Integer.class).snapshot());
            for (ContextMap conflicting : List.of(wrongValue, wrongKey)) {
                assertThrows(ClassCastException.class, conflicting::snapshot);
                assertThrows(ClassCastException.class, conflicting::getOrCreate);
                assertThrows(ClassCastException.class, () -> conflicting.replace(Map.of()));
                assertThrows(ClassCastException.class, conflicting::remove);
                assertThrows(ClassCastException.class, conflicting::clear);
                assertThrows(ClassCastException.class, () -> conflicting.update(current -> {
                    throw new AssertionError("Conflicting declaration reached updater");
                }));
                ContextSlot raw = owner.slot();
                assertThrows(ClassCastException.class, () -> raw.put(conflicting.item(Map.of())));
                assertThrows(ClassCastException.class, () -> raw.update(current -> conflicting.item(Map.of())));
                assertSame(original, owner.slot().get());
            }
            ContextMap<String, Integer> fresh = ContextMaps.define("fresh", String.class, Integer.class);
            ContextSlot rawFresh = fresh.slot();
            assertThrows(ClassCastException.class, () -> rawFresh.getOrCreate(() -> wrongValue.item(Map.of())));
            assertNull(fresh.slot().get());
            AtomicInteger calls = new AtomicInteger();
            Map<String, Integer> input = new HashMap<>(Map.of("created", 1));
            Map<String, Integer> created = fresh.getOrCreate(() -> {
                calls.incrementAndGet();
                return input;
            });
            input.clear();
            assertEquals(Map.of("created", 1), created);
            assertSame(created, fresh.getOrCreate(() -> { throw new AssertionError("Existing map invoked factory"); }));
            assertEquals(1, calls.get());
            test.completeNow();
        }));
    }

    @Test
    void operations_noActiveContext_failWithoutInvokingFactories() {
        ContextMap<String, Integer> map = ContextMaps.define("map", String.class, Integer.class);
        assertThrows(IllegalStateException.class, map::snapshot);
        assertThrows(IllegalStateException.class, map::getOrCreate);
        assertThrows(IllegalStateException.class, () -> map.getOrCreate(() -> { throw new AssertionError("Factory"); }));
        assertThrows(IllegalStateException.class, () -> map.get("entry"));
        assertThrows(IllegalStateException.class, () -> map.put("entry", 1));
        assertThrows(IllegalStateException.class, () -> map.putIfAbsent("entry", 1));
        assertThrows(IllegalStateException.class, () -> map.putAll(Map.of()));
        assertThrows(IllegalStateException.class, () -> map.remove("entry"));
        assertThrows(IllegalStateException.class, map::remove);
        assertThrows(IllegalStateException.class, map::clear);
        assertThrows(IllegalStateException.class, () -> map.replace(Map.of()));
        assertThrows(IllegalStateException.class, () -> map.update(current -> { throw new AssertionError("Updater"); }));
        assertThrows(NullPointerException.class, () -> map.getOrCreate(null));
    }

    @Test
    void update_concurrentSnapshots_keepsEveryEntry(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> {
            ContextInternal caller = ContextInternal.current();
            ContextMap<String, Integer> map = ContextMaps.define("map", String.class, Integer.class);
            Map<String, Integer> initial = map.getOrCreate();
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> pending = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                int id = worker;
                pending.add(caller.executeBlocking(() -> {
                    start.await();
                    assertSame(caller, ContextInternal.current());
                    for (int iteration = 0; iteration < 32; iteration++) {
                        assertNull(map.put(id + ":" + iteration, iteration));
                    }
                    return null;
                }, false));
            }
            start.countDown();
            Future.join(pending).onComplete(test.succeeding(done -> test.verify(() -> {
                    assertSame(caller, ContextInternal.current());
                    assertEquals(256, map.snapshot().size());
                    for (int id = 0; id < 8; id++) {
                        for (int value = 0; value < 32; value++) {
                            assertEquals(value, map.get(id + ":" + value));
                        }
                    }
                    assertTrue(initial.isEmpty());
                    assertInstanceOf(ContextMapItem.class, map.slot().get());
                    test.completeNow();
                })));
        });
    }
}
