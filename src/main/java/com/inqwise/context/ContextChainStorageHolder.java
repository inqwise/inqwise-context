package com.inqwise.context;

import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.inqwise.context.spi.ContextChainDataService;

import io.vertx.core.internal.VertxBootstrap;
import io.vertx.core.internal.logging.Logger;
import io.vertx.core.internal.logging.LoggerFactory;
import io.vertx.core.spi.VertxServiceProvider;
import io.vertx.core.spi.context.storage.ContextLocal;

public class ContextChainStorageHolder implements VertxServiceProvider {
	private static final Logger logger = LoggerFactory.getLogger(ContextChainStorageHolder.class);
	
	@SuppressWarnings("rawtypes")
	static final ContextLocal<ConcurrentMap> STORAGE_CONTEXT = ContextLocal.registerLocal(ConcurrentMap.class);
	
	static final EventBusTransportHandler eventBusInterceptors = new EventBusTransportHandler();
	
	@Override
	public void init(VertxBootstrap builder) {
		
	}
	
	private static List<ContextChainDataService> dataProviders; 
	static List<ContextChainDataService> getDataProviders() {
		var loader = ServiceLoader.load(ContextChainDataService.class);
		if(null == dataProviders) {
			synchronized (ContextChainDataService.class) {
				if(null == dataProviders) {
					dataProviders = ImmutableList.copyOf(loader);
					if(dataProviders.isEmpty()) {
						logger.warn("dataProviders IS EMPTY");
					}
				}
			}
		}
		return dataProviders;
	}
	
	static ContextChainDataService getDataProvider(String key) {
		Objects.requireNonNull(key, "key is mandatory");
		var result = getDataProviders().stream().filter(p -> null != p.key() && p.key() == key).findFirst().orElse(null);
		return Objects.requireNonNull(result, "key not supported");
	}
	
	static Set<String> getDataProviderKeys() {
		return getDataProviders().stream().map(ContextChainDataService::key).collect(Collectors.toSet());
	}
}
