package com.inqwise.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.spi.ContextChainDataService;

class ContextChainStorageHolderTest {
    private static final String BROKEN_PREFIX = "com.inqwise.context.UnavailableProvider";
    private static final String DESCRIPTOR = "META-INF/services/" + ContextChainDataService.class.getName();

    @ParameterizedTest
    @ValueSource(strings = {"runtime", "linkage", "assertion", "service", "bad-cause"})
    void discoverProviders_classLoadingFailures_continueToHealthyProviders(
            String kind, @TempDir Path directory) throws Exception {
        writeDescriptor(directory, BROKEN_PREFIX + "One", BROKEN_PREFIX + "Two", HealthyProvider.class.getName());
        AtomicInteger failures = new AtomicInteger();
        try (URLClassLoader loader = failingProviderLoader(directory, kind, failures)) {
            List<ContextChainDataService> providers = assertDoesNotThrow(() -> ContextChainStorageHolder.discoverProviders(
                ServiceLoader.load(ContextChainDataService.class, loader)));

            assertEquals(2, failures.get());
            assertEquals(List.of(FlowIdService.class, HealthyProvider.class), providers.stream().map(Object::getClass).toList());
            assertThrows(UnsupportedOperationException.class, providers::clear);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime", "linkage", "assertion", "service", "bad-cause"})
    void discoverProviders_nonAdvancingEnumeration_stopsAtBoundAndRetainsHealthyPrefix(
            String kind, @TempDir Path directory) throws Exception {
        AtomicInteger failures = new AtomicInteger();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                Enumeration<URL> resources = super.getResources(name);
                if (!name.equals(DESCRIPTOR)) {
                    return resources;
                }
                return new Enumeration<>() {
                    @Override
                    public boolean hasMoreElements() {
                        if (resources.hasMoreElements()) {
                            return true;
                        }
                        if (failures.incrementAndGet() > 64) {
                            throw new DiscoveryProbeExhausted();
                        }
                        failDiscovery(kind);
                        return false;
                    }

                    @Override
                    public URL nextElement() {
                        return resources.nextElement();
                    }
                };
            }
        }) {
            List<ContextChainDataService> providers = assertDoesNotThrow(() -> ContextChainStorageHolder.discoverProviders(
                ServiceLoader.load(ContextChainDataService.class, loader)));

            assertEquals(64, failures.get());
            assertEquals(List.of(FlowIdService.class), providers.stream().map(Object::getClass).toList());
            assertThrows(UnsupportedOperationException.class, providers::clear);
        }
    }

    @Test
    void discoverProviders_successfulCandidate_resetsConsecutiveFailureBudget(@TempDir Path directory) throws Exception {
        List<String> registrations = new ArrayList<>();
        for (int i = 0; i < 63; i++) {
            registrations.add(BROKEN_PREFIX + "Before" + i);
        }
        registrations.add(HealthyProvider.class.getName());
        for (int i = 0; i < 63; i++) {
            registrations.add(BROKEN_PREFIX + "After" + i);
        }
        registrations.add(LaterHealthyProvider.class.getName());
        writeDescriptor(directory, registrations.toArray(String[]::new));
        AtomicInteger failures = new AtomicInteger();
        try (URLClassLoader loader = failingProviderLoader(directory, "linkage", failures)) {
            List<ContextChainDataService> providers = assertDoesNotThrow(() -> ContextChainStorageHolder.discoverProviders(
                ServiceLoader.load(ContextChainDataService.class, loader)));

            assertEquals(126, failures.get());
            assertEquals(List.of(FlowIdService.class, HealthyProvider.class, LaterHealthyProvider.class),
                providers.stream().map(Object::getClass).toList());
        }
    }

    @Test
    void discoverProviders_fatalVmFailure_isNotRecovered(@TempDir Path directory) throws Exception {
        OutOfMemoryError fatal = new OutOfMemoryError("Controlled fatal discovery failure");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (name.equals(DESCRIPTOR)) {
                    throw fatal;
                }
                return super.getResources(name);
            }
        }) {
            assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> ContextChainStorageHolder.discoverProviders(
                ServiceLoader.load(ContextChainDataService.class, loader))));
        }
    }

    private URLClassLoader failingProviderLoader(Path directory, String kind, AtomicInteger failures) throws IOException {
        return new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(BROKEN_PREFIX)) {
                    failures.incrementAndGet();
                    failDiscovery(kind);
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    private static void failDiscovery(String kind) {
        switch (kind) {
            case "runtime" -> throw new IllegalStateException("Private discovery detail");
            case "linkage" -> throw new NoClassDefFoundError("Private missing dependency");
            case "assertion" -> throw new AssertionError("Private discovery assertion");
            case "service" -> throw new ServiceConfigurationError("Private service discovery detail");
            case "bad-cause" -> throw new UnreadableCauseError();
            default -> throw new IllegalArgumentException("Unknown discovery failure kind");
        }
    }

    private static final class DiscoveryProbeExhausted extends Error {
        private static final long serialVersionUID = 1L;
    }

    private static final class UnreadableCauseError extends ServiceConfigurationError {
        private static final long serialVersionUID = 1L;

        private UnreadableCauseError() {
            super("Private discovery failure");
        }

        @Override
        public Throwable getCause() {
            throw new IllegalStateException("Private diagnostic detail");
        }
    }

    @Test
    void discoverProviders_brokenRegistrations_preserveHealthyProviders(@TempDir Path directory) throws Exception {
        List<ContextChainDataService> providers = discover(directory, "com.inqwise.context.MissingProvider",
            ThrowingProvider.class.getName(), HealthyProvider.class.getName());

        assertEquals(List.of("flow_id", "healthy"), providers.stream().map(ContextChainDataService::key).toList());
        assertThrows(UnsupportedOperationException.class, () -> providers.clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {"exception", "assertion", "service"})
    void discoverProviders_conflictingOrInvalidKeys_keepFirstValidRegistration(
            String failureKind, @TempDir Path directory) throws Exception {
        String broken = switch (failureKind) {
            case "assertion" -> AssertingKeyProvider.class.getName();
            case "service" -> NestedServiceKeyProvider.class.getName();
            default -> ThrowingKeyProvider.class.getName();
        };
        List<ContextChainDataService> providers = discover(directory, DuplicateProvider.class.getName(),
            CaseVariantProvider.class.getName(), MissingKeyProvider.class.getName(),
            broken, HealthyProvider.class.getName());

        assertEquals(List.of(FlowIdService.class, HealthyProvider.class), providers.stream().map(Object::getClass).toList());
        assertEquals(List.of("flow_id", "healthy"), providers.stream().map(ContextChainDataService::key).toList());
    }

    private List<ContextChainDataService> discover(Path directory, String... registrations) throws Exception {
        writeDescriptor(directory, registrations);
        try (var classLoader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader())) {
            return ContextChainStorageHolder.discoverProviders(ServiceLoader.load(ContextChainDataService.class, classLoader));
        }
    }

    private static void writeDescriptor(Path directory, String... registrations) throws IOException {
        Path descriptor = directory.resolve(DESCRIPTOR);
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, String.join("\n", registrations) + "\n");
    }

    public static final class LaterHealthyProvider extends FlowIdService {
        @Override
        public String key() {
            return "later-healthy";
        }
    }

    public static class DuplicateProvider extends FlowIdService {
    }

    public static final class CaseVariantProvider extends FlowIdService {
        @Override
        public String key() {
            return "FLOW_ID";
        }
    }

    public static final class MissingKeyProvider extends FlowIdService {
        @Override
        public String key() {
            return null;
        }
    }

    public static final class ThrowingKeyProvider extends FlowIdService {
        @Override
        public String key() {
            throw new IllegalStateException("Private key configuration detail");
        }
    }

    public static final class AssertingKeyProvider extends FlowIdService {
        @Override
        public String key() {
            throw new AssertionError("Private key configuration detail");
        }
    }

    public static final class NestedServiceKeyProvider extends FlowIdService {
        @Override
        public String key() {
            throw new ServiceConfigurationError("Private nested service configuration", new IllegalStateException("Private cause"));
        }
    }

    public static final class ThrowingProvider extends FlowIdService {
        public ThrowingProvider() {
            super(CodecProfile.LEGACY);
            throw new IllegalStateException("Private configuration detail");
        }
    }

    public static final class HealthyProvider extends FlowIdService {
        public HealthyProvider() {
            super(CodecProfile.LEGACY);
        }

        @Override
        public String key() {
            return "healthy";
        }
    }

	@Test
	void getDataProvidersReturnsServiceLoaderResults() {
		List<ContextChainDataService> providers = ContextChainStorageHolder.getDataProviders();

		assertFalse(providers.isEmpty());
		assertTrue(providers.stream().anyMatch(FlowIdService.class::isInstance));
	}

	@Test
	void getDataProviderReturnsMatchingService() {
		ContextChainDataService provider = ContextChainStorageHolder.getDataProvider("flow_id");

		assertNotNull(provider);
		assertEquals("flow_id", provider.key());
	}

	@Test
	void getDataProviderRequiresNonNullKey() {
		assertThrows(NullPointerException.class, () -> ContextChainStorageHolder.getDataProvider(null));
	}

	@Test
	void getDataProviderKeysMirrorProviderSet() {
		Set<String> keys = ContextChainStorageHolder.getDataProviderKeys();

		assertTrue(keys.contains("flow_id"));
	}
}
