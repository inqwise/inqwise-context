package com.inqwise.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.spi.ContextChainDataService;

class ContextChainStorageHolderTest {

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
