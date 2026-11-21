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
 * Providers and registrations must be visible to this library's defining
 * classloader. Discovery is cached there, independent of the first caller's TCCL.
 * Recoverable iteration failures are skipped, stopping after 64 consecutive
 * failures without a loaded candidate and retaining earlier valid providers.
 * Recoverable diagnostic logging failures do not change provider selection.
 */
public class ContextChainStorageHolder implements VertxServiceProvider {
    private static final int MAX_CONSECUTIVE_DISCOVERY_FAILURES = 64;

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
					var loader = ServiceLoader.load(ContextChainDataService.class,
                        ContextChainStorageHolder.class.getClassLoader());
					result = discoverProviders(loader);
					if (result.isEmpty()) {
						warn("dataProviders IS EMPTY");
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
        int consecutiveFailures = 0;
        while (true) {
            ContextChainDataService provider;
            try {
                if (!candidates.hasNext()) {
                    break;
                }
                provider = candidates.next();
            } catch (RuntimeException | ServiceConfigurationError | LinkageError | AssertionError failure) {
                warnDiscoveryFailure("Skipping unavailable metadata provider", failure);
                // ServiceLoader does not guarantee progress after a discovery failure.
                if (++consecutiveFailures == MAX_CONSECUTIVE_DISCOVERY_FAILURES) {
                    warn("Stopping metadata provider discovery after {} consecutive failures",
                        MAX_CONSECUTIVE_DISCOVERY_FAILURES);
                    break;
                }
                continue;
            }
            consecutiveFailures = 0;
            String key;
            try {
                key = provider.key();
            } catch (RuntimeException | ServiceConfigurationError | LinkageError | AssertionError failure) {
                warn("Skipping metadata provider {}: cannot read header key ({})",
                    provider.getClass().getName(), failure.getClass().getName());
                continue;
            }
            if (key == null || key.isBlank()) {
                warn("Skipping metadata provider {}: missing header key", provider.getClass().getName());
                continue;
            }
            Class<?> previous = registered.putIfAbsent(key.toLowerCase(Locale.ROOT), provider.getClass());
            if (previous != null) {
                warn("Skipping metadata provider {}: header key already registered by {}",
                    provider.getClass().getName(), previous.getName());
                continue;
            }
            providers.add(provider);
        }
        return providers.build();
    }

    private static void warnDiscoveryFailure(String message, Throwable failure) {
        Throwable diagnostic = failure;
        try {
            Throwable cause = failure.getCause();
            if (cause != null) {
                diagnostic = cause;
            }
        } catch (RuntimeException | ServiceConfigurationError | LinkageError | AssertionError unavailable) {
            // Diagnostic accessors must not prevent discovery of later providers.
        }
        warn("{}: {}", message, diagnostic.getClass().getName());
    }

    private static void warn(String message, Object... arguments) {
        try {
            logger.warn(message, arguments);
        } catch (Exception | ServiceConfigurationError | LinkageError | AssertionError loggingFailure) {
            // Diagnostics must not abort discovery or recursively report their own failures.
        }
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
