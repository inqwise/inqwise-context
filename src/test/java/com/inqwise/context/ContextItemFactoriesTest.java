package com.inqwise.context;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.extension.TenantContext;
import com.inqwise.context.spi.ContextItemFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(20)
class ContextItemFactoriesTest {
    @TempDir
    Path directory;

    @Test
    void factory_noProvider_usesFallbackOrRejectsMissingFactory() throws Exception {
        try (URLClassLoader loader = isolated()) {
            Object slot = flowSlot(loader);
            Supplier<?> fallback = () -> "fallback";
            assertSame(fallback, resolve(loader, slot, fallback));
            assertThrows(IllegalStateException.class, () -> resolve(loader, slot, null));
        }
    }

    @Test
    void factory_concurrentResolution_constructsProviderOnceAndIgnoresTccl() throws Exception {
        try (URLClassLoader loader = isolated(Alpha.class.getName());
                var executor = Executors.newFixedThreadPool(8)) {
            Object slot = flowSlot(loader);
            List<java.util.concurrent.Future<Supplier<?>>> pending = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                pending.add(executor.submit(() -> {
                    ClassLoader previous = Thread.currentThread().getContextClassLoader();
                    Thread.currentThread().setContextClassLoader(new ClassLoader(null) {
                        @Override
                        public java.util.Enumeration<URL> getResources(String name) {
                            throw new AssertionError("Factory discovery must not use caller TCCL");
                        }
                    });
                    try {
                        return resolve(loader, slot, () -> { throw new AssertionError("SPI must override fallback"); });
                    } finally {
                        Thread.currentThread().setContextClassLoader(previous);
                    }
                }));
            }
            for (var result : pending) {
                Object first = result.get().get();
                assertEquals("alpha", first.getClass().getMethod("getId").invoke(first));
                assertNotSame(first, result.get().get());
            }
            AtomicInteger calls = (AtomicInteger) loader.loadClass(Alpha.class.getName()).getField("calls").get(null);
            assertEquals(1, calls.get());
        }
    }

    @Test
    void factory_externalSlot_matchesItsExplicitKeyAndType() throws Exception {
        try (URLClassLoader loader = isolated(TenantContext.Factory.class.getName())) {
            Object slot = loader.loadClass(TenantContext.class.getName()).getField("VALUE").get(null);
            Object created = resolve(loader, slot, null).get();
            assertEquals("configured", created.getClass().getMethod("id").invoke(created));
        }
    }

    @Test
    void factory_wrongType_rejectsBeforeCreatingAValue() throws Exception {
        try (URLClassLoader loader = isolated(WrongType.class.getName())) {
            assertThrows(IllegalStateException.class, () -> resolve(loader, flowSlot(loader), null));
            assertEquals(0, ((AtomicInteger) loader.loadClass(WrongType.class.getName()).getField("created").get(null)).get());
        }
    }

    @Test
    void factory_mapParameters_matchIndependentlyOfErasedItemClass() throws Exception {
        try (URLClassLoader loader = isolated(MapFactory.class.getName())) {
            Class<?> maps = loader.loadClass(ContextMaps.class.getName());
            Object matching = maps.getMethod("define", String.class, Class.class, Class.class)
                .invoke(null, "factory-map", String.class, Integer.class);
            Object slot = matching.getClass().getMethod("slot").invoke(matching);
            Object item = resolve(loader, slot, null).get();
            assertEquals(Map.of("configured", 42), item.getClass().getMethod("snapshot").invoke(item));
            for (Class<?>[] parameters : List.of(new Class<?>[] {Long.class, Integer.class},
                    new Class<?>[] {String.class, Long.class})) {
                Object conflict = maps.getMethod("define", String.class, Class.class, Class.class)
                    .invoke(null, "factory-map", parameters[0], parameters[1]);
                Object conflictSlot = conflict.getClass().getMethod("slot").invoke(conflict);
                assertThrows(IllegalStateException.class, () -> resolve(loader, conflictSlot, null));
            }
            assertEquals(1, ((AtomicInteger) loader.loadClass(MapFactory.class.getName()).getField("created").get(null)).get());
        }
    }

    @Test
    void factory_setElementType_matchesIndependentlyOfErasedItemClass() throws Exception {
        try (URLClassLoader loader = isolated(SetFactory.class.getName())) {
            Class<?> sets = loader.loadClass(ContextSets.class.getName());
            Object matching = sets.getMethod("define", String.class, Class.class)
                .invoke(null, "factory-set", String.class);
            Object slot = matching.getClass().getMethod("slot").invoke(matching);
            Object item = resolve(loader, slot, null).get();
            assertEquals(Set.of("configured"), item.getClass().getMethod("snapshot").invoke(item));
            Object conflict = sets.getMethod("define", String.class, Class.class)
                .invoke(null, "factory-set", Long.class);
            Object conflictSlot = conflict.getClass().getMethod("slot").invoke(conflict);
            assertThrows(IllegalStateException.class, () -> resolve(loader, conflictSlot, null));
            assertEquals(1, ((AtomicInteger) loader.loadClass(SetFactory.class.getName()).getField("created").get(null)).get());
        }
    }

    @Test
    void factory_erasedSetSlot_cannotMatchASetOfSetItems() throws Exception {
        try (URLClassLoader loader = isolated(ErasedSetFactory.class.getName())) {
            Class<?> sets = loader.loadClass(ContextSets.class.getName());
            Class<?> itemType = loader.loadClass(ContextSetItem.class.getName());
            Object set = sets.getMethod("define", String.class, Class.class)
                .invoke(null, "factory-set", itemType);
            Object slot = set.getClass().getMethod("slot").invoke(set);
            assertThrows(IllegalStateException.class, () -> resolve(loader, slot, null));
            assertEquals(0, ((AtomicInteger) loader.loadClass(ErasedSetFactory.class.getName()).getField("created").get(null)).get());
        }
    }

    @Test
    void factory_listElementType_matchesIndependentlyOfErasedItemClass() throws Exception {
        try (URLClassLoader loader = isolated(ListFactory.class.getName())) {
            Class<?> lists = loader.loadClass(ContextLists.class.getName());
            Object matching = lists.getMethod("define", String.class, Class.class)
                .invoke(null, "factory-list", String.class);
            Object slot = matching.getClass().getMethod("slot").invoke(matching);
            Object item = resolve(loader, slot, null).get();
            assertEquals(List.of("configured", "configured"), item.getClass().getMethod("snapshot").invoke(item));
            Object conflict = lists.getMethod("define", String.class, Class.class)
                .invoke(null, "factory-list", Long.class);
            Object conflictSlot = conflict.getClass().getMethod("slot").invoke(conflict);
            assertThrows(IllegalStateException.class, () -> resolve(loader, conflictSlot, null));
            assertEquals(1, ((AtomicInteger) loader.loadClass(ListFactory.class.getName()).getField("created").get(null)).get());
        }
    }

    @Test
    void factory_erasedListSlot_cannotMatchAListOfListItems() throws Exception {
        try (URLClassLoader loader = isolated(ErasedListFactory.class.getName())) {
            Class<?> lists = loader.loadClass(ContextLists.class.getName());
            Object list = lists.getMethod("define", String.class, Class.class)
                .invoke(null, "factory-list", loader.loadClass(ContextListItem.class.getName()));
            Object slot = list.getClass().getMethod("slot").invoke(list);
            assertThrows(IllegalStateException.class, () -> resolve(loader, slot, null));
            assertEquals(0, ((AtomicInteger) loader.loadClass(ErasedListFactory.class.getName()).getField("created").get(null)).get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "Failing", "NullSlot", "Missing"})
    void factory_invalidSpi_cachesSanitizedFailure(String scenario) throws Exception {
        String[] providers = scenario.equals("duplicate") ? new String[] {Alpha.class.getName(), Beta.class.getName()}
            : new String[] {ContextItemFactoriesTest.class.getName() + "$" + scenario};
        try (URLClassLoader loader = isolated(providers)) {
            Object slot = flowSlot(loader);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> resolve(loader, slot, null));
            assertSame(failure, assertThrows(IllegalStateException.class, () -> resolve(loader, slot, null)));
            assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains("private-factory-value"));
        }
    }

    private Object flowSlot(ClassLoader loader) throws Exception {
        return loader.loadClass(FlowIdContext.class.getName()).getMethod("slot").invoke(null);
    }

    private Supplier<?> resolve(ClassLoader loader, Object slot, Supplier<?> fallback) throws Exception {
        Class<?> registry = loader.loadClass(ContextItemFactories.class.getName());
        Method method = registry.getDeclaredMethod("factory", loader.loadClass(ContextSlot.class.getName()), Supplier.class);
        method.setAccessible(true);
        try {
            return (Supplier<?>) method.invoke(null, slot, fallback);
        } catch (InvocationTargetException failure) {
            throw assertInstanceOf(IllegalStateException.class, failure.getCause());
        }
    }

    private URLClassLoader isolated(String... providers) throws Exception {
        Path root = Files.createTempDirectory(directory, "factory-loader-");
        if (providers.length > 0) {
            Path service = root.resolve("META-INF/services/" + ContextItemFactory.class.getName());
            Files.createDirectories(service.getParent());
            Files.writeString(service, String.join("\n", providers));
        }
        List<URL> urls = new ArrayList<>();
        urls.add(root.toUri().toURL());
        for (String entry : System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                .split(Pattern.quote(File.pathSeparator))) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    public static class Alpha implements ContextItemFactory<FlowId> {
        public static final AtomicInteger calls = new AtomicInteger();

        public Alpha() {
            calls.incrementAndGet();
        }

        @Override
        public ContextSlot<FlowId> slot() {
            return FlowIdContext.slot();
        }

        @Override
        public FlowId create() {
            return FlowId.builder().withId("alpha").build();
        }
    }

    public static final class Beta extends Alpha {
    }

    public static final class Failing extends Alpha {
        public Failing() {
            throw new IllegalStateException("private-factory-value");
        }
    }

    public static final class NullSlot extends Alpha {
        @Override
        public ContextSlot<FlowId> slot() {
            return null;
        }
    }

    public static final class WrongType implements ContextItemFactory<TenantContext.Tenant> {
        public static final AtomicInteger created = new AtomicInteger();

        @Override
        public ContextSlot<TenantContext.Tenant> slot() {
            return ContextSlots.define("flow_id", TenantContext.Tenant.class);
        }

        @Override
        public TenantContext.Tenant create() {
            created.incrementAndGet();
            return new TenantContext.Tenant("wrong");
        }
    }

    public static final class MapFactory implements ContextItemFactory<ContextMapItem<String, Integer>> {
        public static final AtomicInteger created = new AtomicInteger();
        private static final ContextMap<String, Integer> MAP = ContextMaps.define("factory-map", String.class, Integer.class);

        @Override
        public ContextSlot<ContextMapItem<String, Integer>> slot() {
            return MAP.slot();
        }

        @Override
        public ContextMapItem<String, Integer> create() {
            created.incrementAndGet();
            return MAP.item(Map.of("configured", 42));
        }
    }

    public static final class SetFactory implements ContextItemFactory<ContextSetItem<String>> {
        public static final AtomicInteger created = new AtomicInteger();
        private static final ContextSet<String> SET = ContextSets.define("factory-set", String.class);

        @Override
        public ContextSlot<ContextSetItem<String>> slot() {
            return SET.slot();
        }

        @Override
        public ContextSetItem<String> create() {
            created.incrementAndGet();
            return SET.item(Set.of("configured"));
        }
    }

    @SuppressWarnings("rawtypes")
    public static final class ErasedSetFactory implements ContextItemFactory<ContextSetItem> {
        public static final AtomicInteger created = new AtomicInteger();

        @Override
        public ContextSlot<ContextSetItem> slot() {
            return ContextSlots.define("factory-set", ContextSetItem.class);
        }

        @Override
        public ContextSetItem create() {
            created.incrementAndGet();
            return ContextSets.define("factory-set", String.class).item(Set.of());
        }
    }

    public static final class ListFactory implements ContextItemFactory<ContextListItem<String>> {
        public static final AtomicInteger created = new AtomicInteger();
        private static final ContextList<String> LIST = ContextLists.define("factory-list", String.class);

        @Override
        public ContextSlot<ContextListItem<String>> slot() {
            return LIST.slot();
        }

        @Override
        public ContextListItem<String> create() {
            created.incrementAndGet();
            return LIST.item(List.of("configured", "configured"));
        }
    }

    @SuppressWarnings("rawtypes")
    public static final class ErasedListFactory implements ContextItemFactory<ContextListItem> {
        public static final AtomicInteger created = new AtomicInteger();

        @Override
        public ContextSlot<ContextListItem> slot() {
            return ContextSlots.define("factory-list", ContextListItem.class);
        }

        @Override
        public ContextListItem create() {
            created.incrementAndGet();
            return ContextLists.define("factory-list", String.class).item(List.of());
        }
    }
}
