package com.inqwise.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.spi.ContextChainDataService;

class ContextChainStorageHolderTest {

    @Test
    void discoverProviders_brokenRegistrations_preserveHealthyProviders(@TempDir Path directory) throws Exception {
        List<ContextChainDataService> providers = discover(directory, "com.inqwise.context.MissingProvider",
            ThrowingProvider.class.getName(), HealthyProvider.class.getName());

        assertEquals(List.of("flow_id", "healthy"), providers.stream().map(ContextChainDataService::key).toList());
        assertThrows(UnsupportedOperationException.class, () -> providers.clear());
    }

    @Test
    void discoverProviders_conflictingOrInvalidKeys_keepFirstValidRegistration(@TempDir Path directory) throws Exception {
        List<ContextChainDataService> providers = discover(directory, DuplicateProvider.class.getName(),
            CaseVariantProvider.class.getName(), MissingKeyProvider.class.getName(),
            ThrowingKeyProvider.class.getName(), HealthyProvider.class.getName());

        assertEquals(List.of(FlowIdService.class, HealthyProvider.class), providers.stream().map(Object::getClass).toList());
        assertEquals(List.of("flow_id", "healthy"), providers.stream().map(ContextChainDataService::key).toList());
    }

    private List<ContextChainDataService> discover(Path directory, String... registrations) throws Exception {
        Path descriptor = directory.resolve("META-INF/services/" + ContextChainDataService.class.getName());
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, String.join("\n", registrations) + "\n");
        try (var classLoader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader())) {
            return ContextChainStorageHolder.discoverProviders(ServiceLoader.load(ContextChainDataService.class, classLoader));
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
