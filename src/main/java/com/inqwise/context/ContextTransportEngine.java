package com.inqwise.context;

import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.function.Supplier;

import com.inqwise.context.ContextPropagationException.Direction;
import com.inqwise.context.spi.ContextChainDataService;
import com.inqwise.context.spi.ContextTransportCarrier;
import io.vertx.core.internal.ContextInternal;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class ContextTransportEngine {
    private static final Logger logger = LogManager.getLogger(ContextTransportEngine.class);
    private final Supplier<List<ContextChainDataService>> dataProviders;

    ContextTransportEngine(Supplier<List<ContextChainDataService>> dataProviders) {
        this.dataProviders = dataProviders;
    }

    void read(ContextInternal context, ContextTransportCarrier carrier, Set<String> includedKeys) {
        if (includedKeys != null && includedKeys.isEmpty()) {
            return;
        }
        try {
            for (var provider : dataProviders.get()) {
                try {
                    String key = provider.key();
                    if (includedKeys != null && !includedKeys.contains(key)) {
                        continue;
                    }
                    boolean replaceExisting = provider.replaceExistingOnInbound();
                    if (!replaceExisting && DefaultStorage.getEntry(context, key) != null) {
                        continue;
                    }
                    List<String> values = Objects.requireNonNull(carrier.getAll(key), "Header values are mandatory");
                    if (values.isEmpty()) {
                        continue;
                    }
                    if (values.size() != 1) {
                        throw new IllegalArgumentException("Expected exactly one metadata header value");
                    }
                    String encoded = Objects.requireNonNull(values.getFirst(), "Encoded metadata is mandatory");
                    if (provider.isRemoval(encoded)) {
                        DefaultStorage.remove(context, key, replaceExisting);
                    } else {
                        DefaultStorage.put(context, key, provider.unmarshalFormString(encoded), replaceExisting);
                    }
                } catch (Exception | ServiceConfigurationError | LinkageError | AssertionError failure) {
                    reportFailure(context, Direction.INBOUND, provider, failure);
                }
            }
        } catch (Throwable failure) {
            reportFailure(context, Direction.INBOUND, null, failure);
        }
    }

    void write(ContextInternal context, ContextTransportCarrier carrier, Set<String> includedKeys) {
        if (includedKeys != null && includedKeys.isEmpty()) {
            return;
        }
        try {
            for (var provider : dataProviders.get()) {
                try {
                    String key = provider.key();
                    if (includedKeys != null && !includedKeys.contains(key)) {
                        continue;
                    }
                    var value = DefaultStorage.getEntry(context, key);
                    if (value != null) {
                        // Never fall back to stale metadata if the current state cannot be encoded.
                        carrier.remove(key);
                        String encoded = DefaultStorage.isRemoved(value) ? provider.marshalRemovalToString()
                            : Objects.requireNonNull(provider.marshalToString(value), "Encoded metadata is mandatory");
                        if (encoded != null) {
                            carrier.set(key, encoded);
                        }
                    }
                } catch (Exception | ServiceConfigurationError | LinkageError | AssertionError failure) {
                    reportFailure(context, Direction.OUTBOUND, provider, failure);
                }
            }
        } catch (Throwable failure) {
            reportFailure(context, Direction.OUTBOUND, null, failure);
        }
    }

    static void reportFailure(ContextInternal context, Direction direction,
            ContextChainDataService provider, Throwable error) {
        ContextPropagationException failure = new ContextPropagationException(direction, provider, error);
        try {
            context.reportException(failure);
        } catch (Throwable observerError) {
            // An application's error observer must not interrupt optional metadata transport.
            try {
                logger.error("{}; exception handler failed: {}", failure.getMessage(), observerError.getClass().getName());
            } catch (Exception | ServiceConfigurationError | LinkageError | AssertionError loggingFailure) {
                // Reporting is best effort; never recurse when its fallback fails.
            }
        }
    }
}
