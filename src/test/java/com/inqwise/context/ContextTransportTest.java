package com.inqwise.context;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.inqwise.context.spi.ContextTransportConfigurationProvider;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextTransportTest {
    private static final String SECRET = "private-transport-configuration";

    @TempDir
    Path temporary;

    @Test
    void defaultConfiguration_noProvider_usesSharedFlowIdOnlyDefault() throws Exception {
        try (var loader = isolated(temporary)) {
            Class<?> api = loader.loadClass(ContextTransport.class.getName());
            Object first = api.getMethod("defaultConfiguration").invoke(null);

            assertEquals(Set.of("flow_id"), keys(first));
            assertSame(first, api.getMethod("defaultConfiguration").invoke(null));
            Class<?> configuration = loader.loadClass(ContextTransportConfiguration.class.getName());
            assertSame(first, api.getMethod("resolveConfiguration", configuration).invoke(null, new Object[] {null}));
        }
    }

    @Test
    void defaultConfiguration_concurrentCalls_evaluateSpiOnce() throws Exception {
        try (var loader = isolated(temporary, Alpha.class.getName());
                var executor = Executors.newFixedThreadPool(8)) {
            Class<?> api = loader.loadClass(ContextTransport.class.getName());
            List<Future<Object>> pending = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                pending.add(executor.submit(() -> api.getMethod("defaultConfiguration").invoke(null)));
            }
            Object first = pending.getFirst().get(5, TimeUnit.SECONDS);
            for (Future<Object> call : pending) {
                assertSame(first, call.get(5, TimeUnit.SECONDS));
            }
            assertEquals(Set.of("alpha"), keys(first));
            assertEquals(1, calls(loader, Alpha.class));
        }
    }

    @Test
    void defaultConfiguration_distinctLibraryClassloaders_resolveIndependentDefaults() throws Exception {
        try (var alpha = isolated(temporary.resolve("a"), Alpha.class.getName());
                var beta = isolated(temporary.resolve("b"), Beta.class.getName())) {
            Object first = alpha.loadClass(ContextTransport.class.getName()).getMethod("defaultConfiguration").invoke(null);
            Object second = beta.loadClass(ContextTransport.class.getName()).getMethod("defaultConfiguration").invoke(null);

            assertEquals(Set.of("alpha"), keys(first));
            assertEquals(Set.of("beta"), keys(second));
            assertNotSame(first.getClass().getClassLoader(), second.getClass().getClassLoader());
        }
    }

    @Test
    void resolveConfiguration_ambiguousSpi_explicitOverrideBypassesDiscovery() throws Exception {
        try (var loader = isolated(temporary, Alpha.class.getName(), Beta.class.getName())) {
            Class<?> api = loader.loadClass(ContextTransport.class.getName());
            Class<?> configuration = loader.loadClass(ContextTransportConfiguration.class.getName());
            Object builder = configuration.getMethod("builder").invoke(null);
            builder.getClass().getMethod("include", String.class).invoke(builder, "explicit");
            Object explicit = builder.getClass().getMethod("build").invoke(builder);

            assertSame(explicit, api.getMethod("resolveConfiguration", configuration).invoke(null, explicit));
            assertEquals(0, calls(loader, Alpha.class));
            assertEquals(0, calls(loader, Beta.class));
            IllegalStateException failure = defaultFailure(api);
            assertTrue(failure.getMessage().contains("Multiple transport configuration providers"));
            assertSame(failure, defaultFailure(api));
            assertSame(explicit, api.getMethod("resolveConfiguration", configuration).invoke(null, explicit));
            assertEquals(0, calls(loader, Alpha.class));
            assertEquals(0, calls(loader, Beta.class));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Failing", "FailingConstructor", "FailingAssertion", "FailingLinkage",
        "FailingServiceError", "NullConfiguration", "Missing"})
    void defaultConfiguration_invalidSpi_cachesSanitizedFailure(String provider) throws Exception {
        String name = ContextTransportTest.class.getName() + "$" + provider;
        try (var loader = isolated(temporary, name)) {
            Class<?> api = loader.loadClass(ContextTransport.class.getName());
            Class<?> configuration = loader.loadClass(ContextTransportConfiguration.class.getName());
            Object builder = configuration.getMethod("builder").invoke(null);
            Object explicit = builder.getClass().getMethod("build").invoke(builder);
            assertSame(explicit, api.getMethod("resolveConfiguration", configuration).invoke(null, explicit));
            if (!provider.equals("Missing")) {
                assertEquals(0, ((AtomicInteger) loader.loadClass(name).getField("calls").get(null)).get());
            }
            IllegalStateException failure = defaultFailure(api);

            assertSame(failure, defaultFailure(api));
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains(SECRET));
            assertSame(explicit, api.getMethod("resolveConfiguration", configuration).invoke(null, explicit));
            assertSame(failure, defaultFailure(api));
            if (!provider.equals("Missing")) {
                AtomicInteger count = (AtomicInteger) loader.loadClass(name).getField("calls").get(null);
                assertEquals(1, count.get());
            }
        }
    }

    @Test
    void defaultConfiguration_concurrentProviderAssertion_cachesOneSanitizedFailure() throws Exception {
        try (var loader = isolated(temporary, FailingAssertion.class.getName());
                var executor = Executors.newFixedThreadPool(8)) {
            Class<?> api = loader.loadClass(ContextTransport.class.getName());
            List<Future<IllegalStateException>> pending = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                pending.add(executor.submit(() -> defaultFailure(api)));
            }
            IllegalStateException first = pending.getFirst().get(5, TimeUnit.SECONDS);
            for (Future<IllegalStateException> call : pending) {
                assertSame(first, call.get(5, TimeUnit.SECONDS));
            }
            assertNull(first.getCause());
            assertFalse(first.toString().contains(SECRET));
            assertTrue(first.getMessage().contains(AssertionError.class.getName()));
            assertEquals(1, calls(loader, FailingAssertion.class));
        }
    }

    private URLClassLoader isolated(Path directory, String... providers) throws Exception {
        if (providers.length > 0) {
            Path descriptor = directory.resolve("META-INF/services/" + ContextTransportConfigurationProvider.class.getName());
            Files.createDirectories(descriptor.getParent());
            Files.writeString(descriptor, String.join("\n", providers) + "\n");
        } else {
            Files.createDirectories(directory);
        }
        // Define the library itself separately; changing only TCCL must not select a default.
        return new URLClassLoader(new URL[] {directory.toUri().toURL(),
            ContextTransport.class.getProtectionDomain().getCodeSource().getLocation(),
            getClass().getProtectionDomain().getCodeSource().getLocation()}, ClassLoader.getPlatformClassLoader());
    }

    private static Set<?> keys(Object configuration) throws Exception {
        return (Set<?>) configuration.getClass().getMethod("includedKeys").invoke(configuration);
    }

    private static int calls(ClassLoader loader, Class<?> provider) throws Exception {
        return ((AtomicInteger) loader.loadClass(provider.getName()).getField("calls").get(null)).get();
    }

    private static IllegalStateException defaultFailure(Class<?> api) {
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
            () -> api.getMethod("defaultConfiguration").invoke(null));
        return assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    public static final class Alpha implements ContextTransportConfigurationProvider {
        public static final AtomicInteger calls = new AtomicInteger();

        @Override
        public ContextTransportConfiguration configuration() {
            calls.incrementAndGet();
            return ContextTransportConfiguration.builder().include("alpha").build();
        }
    }

    public static final class Beta implements ContextTransportConfigurationProvider {
        public static final AtomicInteger calls = new AtomicInteger();

        @Override
        public ContextTransportConfiguration configuration() {
            calls.incrementAndGet();
            return ContextTransportConfiguration.builder().include("beta").build();
        }
    }

    public static final class Failing implements ContextTransportConfigurationProvider {
        public static final AtomicInteger calls = new AtomicInteger();

        @Override
        public ContextTransportConfiguration configuration() {
            calls.incrementAndGet();
            throw new IllegalStateException(SECRET);
        }
    }

    public static final class NullConfiguration implements ContextTransportConfigurationProvider {
        public static final AtomicInteger calls = new AtomicInteger();

        @Override
        public ContextTransportConfiguration configuration() {
            calls.incrementAndGet();
            return null;
        }
    }

    public static final class FailingAssertion implements ContextTransportConfigurationProvider {
        public static final AtomicInteger calls = new AtomicInteger();

        @Override
        public ContextTransportConfiguration configuration() {
            calls.incrementAndGet();
            throw new AssertionError(SECRET);
        }
    }

    public static final class FailingLinkage implements ContextTransportConfigurationProvider {
        public static final AtomicInteger calls = new AtomicInteger();

        @Override
        public ContextTransportConfiguration configuration() {
            calls.incrementAndGet();
            throw new NoClassDefFoundError(SECRET);
        }
    }

    public static final class FailingServiceError implements ContextTransportConfigurationProvider {
        public static final AtomicInteger calls = new AtomicInteger();

        @Override
        public ContextTransportConfiguration configuration() {
            calls.incrementAndGet();
            throw new ServiceConfigurationError(SECRET);
        }
    }

    public static final class FailingConstructor implements ContextTransportConfigurationProvider {
        public static final AtomicInteger calls = new AtomicInteger();

        public FailingConstructor() {
            calls.incrementAndGet();
            throw new IllegalStateException(SECRET);
        }

        @Override
        public ContextTransportConfiguration configuration() {
            throw new AssertionError("A failed provider must not be called");
        }
    }
}
