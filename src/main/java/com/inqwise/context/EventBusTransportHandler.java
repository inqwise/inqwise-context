package com.inqwise.context;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import com.inqwise.context.spi.ContextChainDataService;
import com.inqwise.context.ContextPropagationException.Direction;

import io.vertx.core.eventbus.DeliveryContext;
import io.vertx.core.internal.ContextInternal;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

class EventBusTransportHandler {
    private static final Logger logger = LogManager.getLogger(EventBusTransportHandler.class);

    private final Supplier<List<ContextChainDataService>> dataProviders;

    EventBusTransportHandler() {
        this(ContextChainStorageHolder::getDataProviders);
    }

    EventBusTransportHandler(Supplier<List<ContextChainDataService>> dataProviders) {
        this.dataProviders = dataProviders;
    }

    public void handleInbound(DeliveryContext<Object> event) {
        ContextInternal context = ContextInternal.current();
        try {
            if (context != null) {
                var headers = event.message().headers();
                for (var provider : dataProviders.get()) {
                    try {
                        String key = provider.key();
                        if (!headers.contains(key)) {
                            continue;
                        }
                        boolean replaceExisting = provider.replaceExistingOnInbound();
                        if (!replaceExisting && DefaultStorage.getEntry(context, key) != null) {
                            continue;
                        }
                        List<String> values = headers.getAll(key);
                        if (values.size() != 1) {
                            throw new IllegalArgumentException("Expected exactly one metadata header value");
                        }
                        String headerValue = values.getFirst();
                        if (provider.isRemoval(headerValue)) {
                            DefaultStorage.remove(context, key, replaceExisting);
                        } else {
                            DefaultStorage.put(context, key, provider.unmarshalFormString(headerValue), replaceExisting);
                        }
                    } catch (Exception e) {
                        reportFailure(context, Direction.INBOUND, provider, e);
                    }
                }
            }
        } catch (Throwable e) {
            reportFailure(context, Direction.INBOUND, null, e);
        } finally {
            event.next();
        }
    }

    public void handleOutbound(DeliveryContext<Object> event) {
        ContextInternal context = ContextInternal.current();
        try {
            if (context != null) {
                for (var provider : dataProviders.get()) {
                    try {
                        String key = provider.key();
                        var storageValue = DefaultStorage.getEntry(context, key);
                        if (storageValue != null) {
                            var headers = event.message().headers();
                            // Never fall back to stale metadata if the current state cannot be encoded.
                            headers.remove(key);
                            String encoded = DefaultStorage.isRemoved(storageValue)
                                ? provider.marshalRemovalToString()
                                : Objects.requireNonNull(provider.marshalToString(storageValue), "Encoded metadata is mandatory");
                            if (encoded != null) {
                                headers.set(key, encoded);
                            }
                        }
                    } catch (Exception e) {
                        reportFailure(context, Direction.OUTBOUND, provider, e);
                    }
                }
            }
        } catch (Throwable e) {
            reportFailure(context, Direction.OUTBOUND, null, e);
        } finally {
            event.next();
        }
    }

    private static void reportFailure(ContextInternal context, Direction direction,
            ContextChainDataService provider, Throwable error) {
        ContextPropagationException failure = new ContextPropagationException(direction, provider, error);
        try {
            context.reportException(failure);
        } catch (Throwable observerError) {
            // An application's error observer must not interrupt optional metadata transport.
            logger.error("{}; exception handler failed: {}", failure.getMessage(), observerError.getClass().getName());
        }
    }
}
