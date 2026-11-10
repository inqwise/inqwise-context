package com.inqwise.context;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.inqwise.context.spi.ContextChainDataService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.vertx.core.internal.VertxBootstrap;
import io.vertx.core.spi.VertxServiceProvider;
import io.vertx.core.spi.context.storage.ContextLocal;

/**
 * Registers the shared context-local slot and discovers metadata providers.
 */
public class ContextChainStorageHolder implements VertxServiceProvider {
    /**
     * Creates the service provider used during Vert.x bootstrap.
     */
    public ContextChainStorageHolder() {
    }

	private static final Logger logger = LogManager.getLogger(ContextChainStorageHolder.class);
	
	@SuppressWarnings("rawtypes")
	static final ContextLocal<ConcurrentMap> STORAGE_CONTEXT = ContextLocal.registerLocal(ConcurrentMap.class);
	
	static final EventBusTransportHandler eventBusInterceptors = new EventBusTransportHandler();
	
	@Override
	public void init(VertxBootstrap builder) {
		
	}
	
	private static volatile List<ContextChainDataService> dataProviders; 
	static List<ContextChainDataService> getDataProviders() {
		var result = dataProviders;
		if (null == result) {
			synchronized (ContextChainDataService.class) {
				result = dataProviders;
				if (null == result) {
					var loader = ServiceLoader.load(ContextChainDataService.class);
					result = discoverProviders(loader);
					if (result.isEmpty()) {
						logger.warn("dataProviders IS EMPTY");
					}
					dataProviders = result;
				}
			}
		}
		return result;
	}

    static List<ContextChainDataService> discoverProviders(ServiceLoader<ContextChainDataService> loader) {
        var providers = ImmutableList.<ContextChainDataService>builder();
        Map<String, Class<?>> registered = new HashMap<>();
        var candidates = loader.iterator();
        while (true) {
            try {
                if (!candidates.hasNext()) {
                    break;
                }
            } catch (ServiceConfigurationError failure) {
                // The iterator may not be able to advance after an enumeration failure.
                warnDiscoveryFailure("Cannot continue metadata provider discovery", failure);
                break;
            }
            ContextChainDataService provider;
            try {
                provider = candidates.next();
            } catch (ServiceConfigurationError failure) {
                warnDiscoveryFailure("Skipping unavailable metadata provider", failure);
                continue;
            }
            String key;
            try {
                key = provider.key();
            } catch (RuntimeException | LinkageError failure) {
                logger.warn("Skipping metadata provider {}: cannot read header key ({})",
                    provider.getClass().getName(), failure.getClass().getName());
                continue;
            }
            if (key == null || key.isBlank()) {
                logger.warn("Skipping metadata provider {}: missing header key", provider.getClass().getName());
                continue;
            }
            Class<?> previous = registered.putIfAbsent(key.toLowerCase(Locale.ROOT), provider.getClass());
            if (previous != null) {
                logger.warn("Skipping metadata provider {}: header key already registered by {}",
                    provider.getClass().getName(), previous.getName());
                continue;
            }
            providers.add(provider);
        }
        return providers.build();
    }

    private static void warnDiscoveryFailure(String message, ServiceConfigurationError failure) {
        Throwable cause = failure.getCause();
        logger.warn("{}: {}", message, (cause == null ? failure : cause).getClass().getName());
    }
	
	static ContextChainDataService getDataProvider(String key) {
		Objects.requireNonNull(key, "key is mandatory");
		var result = getDataProviders().stream().filter(p -> key.equals(p.key())).findFirst().orElse(null);
		return Objects.requireNonNull(result, "key not supported");
	}
	
	static Set<String> getDataProviderKeys() {
		return getDataProviders().stream().map(ContextChainDataService::key).collect(Collectors.toSet());
	}
}
