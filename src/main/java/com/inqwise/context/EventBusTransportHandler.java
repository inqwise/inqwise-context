package com.inqwise.context;

import java.util.List;
import java.util.function.Supplier;

import com.inqwise.context.spi.ContextChainDataService;
import com.inqwise.context.spi.ContextTransportCarrier;
import io.vertx.core.eventbus.DeliveryContext;
import io.vertx.core.internal.ContextInternal;

class EventBusTransportHandler {
    private final ContextTransportEngine engine;

    EventBusTransportHandler() {
        this(ContextChainStorageHolder::getDataProviders);
    }

    EventBusTransportHandler(Supplier<List<ContextChainDataService>> dataProviders) {
        engine = new ContextTransportEngine(dataProviders);
    }

    public void handleInbound(DeliveryContext<Object> event) {
        ContextInternal context = ContextInternal.current();
        try {
            if (context != null) {
                engine.read(context, new Headers(event), null);
            }
        } finally {
            event.next();
        }
    }

    public void handleOutbound(DeliveryContext<Object> event) {
        ContextInternal context = ContextInternal.current();
        try {
            if (context != null) {
                engine.write(context, new Headers(event), null);
            }
        } finally {
            event.next();
        }
    }

    private record Headers(DeliveryContext<Object> event) implements ContextTransportCarrier {
        @Override
        public List<String> getAll(String key) {
            return event.message().headers().getAll(key);
        }

        @Override
        public void set(String key, String value) {
            event.message().headers().set(key, value);
        }

        @Override
        public void remove(String key) {
            event.message().headers().remove(key);
        }
    }
}
