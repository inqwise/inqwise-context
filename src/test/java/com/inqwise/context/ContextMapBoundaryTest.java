package com.inqwise.context;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.spi.ContextChainDataItem;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class ContextMapBoundaryTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rawImport_competingDeclarations_onlyOneTypeCanClaimTheItem(boolean differentKey, Vertx vertx) throws Exception {
        ContextInternal caller = (ContextInternal) vertx.getOrCreateContext();
        ContextMapItem<String, Integer> first = ContextMaps.define("map", String.class, Integer.class).item(Map.of());
        ContextChainDataItem second = differentKey
            ? ContextMaps.define("map", Long.class, Integer.class).item(Map.of())
            : ContextMaps.define("map", String.class, Long.class).item(Map.of());
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int iteration = 0; iteration < 64; iteration++) {
                DefaultStorage.remove(caller, "map", true);
                CountDownLatch start = new CountDownLatch(1);
                var firstWrite = executor.submit(() -> attemptImport(caller, first, start));
                var secondWrite = executor.submit(() -> attemptImport(caller, second, start));
                start.countDown();
                boolean firstSucceeded = firstWrite.get(5, TimeUnit.SECONDS);
                boolean secondSucceeded = secondWrite.get(5, TimeUnit.SECONDS);
                assertNotEquals(firstSucceeded, secondSucceeded);
                assertSame(firstSucceeded ? first : second, DefaultStorage.get(caller, "map"));
            }
        }
    }

    private static boolean attemptImport(ContextInternal context, ContextChainDataItem item, CountDownLatch start)
            throws InterruptedException {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try {
            DefaultStorage.put(context, "map", item, true);
            return true;
        } catch (ClassCastException conflict) {
            return false;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rawImport_incompatibleMapOrItem_retainsExistingMapEvenWhenEmpty(
            boolean populated, Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextInternal caller = ContextInternal.current();
            ContextMap<String, Integer> owner = ContextMaps.define("map", String.class, Integer.class);
            owner.replace(populated ? Map.of("entry", 1) : Map.of());
            ContextMapItem<String, Integer> original = owner.slot().require();
            ContextChainDataItem[] conflicts = {
                ContextMaps.define("map", String.class, Long.class).item(Map.of()),
                ContextMaps.define("map", Integer.class, Integer.class).item(Map.of()),
                FlowId.builder().withId("not-a-map").build()
            };
            for (ContextChainDataItem incoming : conflicts) {
                assertThrows(ClassCastException.class, () -> DefaultStorage.put(caller, "map", incoming, true));
                assertSame(original, owner.slot().require());
                DefaultStorage.put(caller, "map", incoming, false);
                assertSame(original, owner.slot().require());
            }
            test.completeNow();
        }));
    }

    @Test
    void rawImport_mapCannotReplaceUnrelatedItem_withoutExplicitRemoval(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextInternal caller = ContextInternal.current();
            FlowId original = FlowId.builder().withId("original").build();
            DefaultStorage.put(caller, "map", original, true);
            ContextMap<String, Integer> map = ContextMaps.define("map", String.class, Integer.class);
            ContextMapItem<String, Integer> incoming = map.item(Map.of());
            assertThrows(ClassCastException.class, () -> DefaultStorage.put(caller, "map", incoming, true));
            assertSame(original, DefaultStorage.get(caller, "map"));
            DefaultStorage.remove(caller, "map", true);
            DefaultStorage.put(caller, "map", incoming, true);
            assertSame(incoming, map.slot().require());
            test.completeNow();
        }));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rawSlot_erasedClass_cannotChangeExistingMapParameters(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextMap<String, Integer> owner = ContextMaps.define("map", String.class, Integer.class);
            owner.replace(Map.of());
            ContextMapItem<String, Integer> original = owner.slot().require();
            ContextChainDataItem conflicting = ContextMaps.define("map", String.class, Long.class).item(Map.of());
            ContextSlot erased = ContextSlots.define("map", (Class) ContextMapItem.class);
            assertThrows(ClassCastException.class, () -> erased.put(conflicting));
            assertSame(original, owner.slot().require());
            assertThrows(ClassCastException.class, () -> erased.update(current -> conflicting));
            assertSame(original, owner.slot().require());
            test.completeNow();
        }));
    }

    @Test
    void rawImport_matchingParametersAndRemoval_followExistingLifecycle(Vertx vertx, VertxTestContext test) {
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextInternal caller = ContextInternal.current();
            ContextMap<String, Integer> owner = ContextMaps.define("map", String.class, Integer.class);
            ContextMapItem<String, Integer> first = owner.item(Map.of("first", 1));
            DefaultStorage.put(caller, "map", first, true);
            assertSame(first, owner.slot().require());
            ContextMapItem<String, Integer> second = owner.item(Map.of("second", 2));
            DefaultStorage.put(caller, "map", second, true);
            assertSame(second, owner.slot().require());
            DefaultStorage.remove(caller, "map", true);
            assertTrue(DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "map")));
            assertThrows(IllegalStateException.class, owner::getOrCreate);
            ContextMap<String, Long> fresh = ContextMaps.define("map", String.class, Long.class);
            DefaultStorage.put(caller, "map", fresh.item(Map.of("new", 3L)), true);
            assertEquals(Map.of("new", 3L), fresh.snapshot());
            assertThrows(ClassCastException.class, owner::snapshot);
            test.completeNow();
        }));
    }
}
