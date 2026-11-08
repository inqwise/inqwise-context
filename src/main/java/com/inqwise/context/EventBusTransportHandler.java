package com.inqwise.context;

import java.util.List;
import java.util.function.Supplier;

import com.inqwise.context.spi.ContextChainDataService;

import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryContext;
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
        try {
            if (Vertx.currentContext() != null) {
                var headers = event.message().headers();
                for (var provider : dataProviders.get()) {
                    try {
                        var headerValue = headers.get(provider.key());
                        if (headerValue != null) {
                            DefaultStorage.put(provider.key(), provider.unmarshalFormString(headerValue));
                        }
                    } catch (Exception e) {
                        logProviderFailure("inbound", provider, e);
                    }
                }
            }
        } catch (Throwable e) {
            logger.error("unexpected error in handleInbound", e);
        }
        event.next();
    }

    public void handleOutbound(DeliveryContext<Object> event) {
        try {
            if (Vertx.currentContext() != null) {
                for (var provider : dataProviders.get()) {
                    try {
                        var storageValue = DefaultStorage.get(provider.key());
                        if (storageValue != null) {
                            event.message().headers().set(provider.key(), provider.marshalToString(storageValue));
                        }
                    } catch (Exception e) {
                        logProviderFailure("outbound", provider, e);
                    }
                }
            }
        } catch (Throwable e) {
            logger.error("unexpected error in handleOutbound", e);
        }
        event.next();
    }

    private static void logProviderFailure(String direction, ContextChainDataService provider, Exception error) {
        // Serializer exception messages can contain sensitive header values.
        logger.error("Context propagation failed: direction={}, provider={}, exception={}",
            direction, provider.getClass().getName(), error.getClass().getName());
    }
}
