package com.inqwise.context;

import java.util.concurrent.atomic.AtomicInteger;

import com.inqwise.context.spi.ContextChainDataItem;
import com.inqwise.context.spi.ContextChainDataService;
import com.inqwise.context.spi.ContextTransportConfigurationProvider;

/** Client-independent codec fixture using the default replace-existing policy. */
public final class CustomMetadataTestProvider implements ContextChainDataService {
    static final String KEY = "custom_state";
    static final String VALUE = "custom:1:value:";
    static final String REMOVAL = "custom:1:remove:";
    static final AtomicInteger constructed = new AtomicInteger();

    /** Records codec discovery in the forked application. */
    public CustomMetadataTestProvider() {
        constructed.incrementAndGet();
    }

    record Value(String text) implements ContextChainDataItem {
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String marshalToString(ContextChainDataItem item) {
        return VALUE + ((Value) item).text();
    }

    @Override
    public ContextChainDataItem unmarshalFormString(String encoded) {
        if (!encoded.startsWith(VALUE)) {
            throw new IllegalArgumentException("Invalid custom metadata envelope");
        }
        return new Value(encoded.substring(VALUE.length()));
    }

    @Override
    public String marshalRemovalToString() {
        return REMOVAL;
    }

    @Override
    public boolean isRemoval(String encoded) {
        return REMOVAL.equals(encoded);
    }

    /** Application-wide metadata selection supplied through SPI. */
    public static final class Configuration implements ContextTransportConfigurationProvider {
        static final AtomicInteger calls = new AtomicInteger();

        /** Creates the application default provider. */
        public Configuration() {
        }

        @Override
        public ContextTransportConfiguration configuration() {
            calls.incrementAndGet();
            return ContextTransportConfiguration.builder().include("flow_id").include(KEY).build();
        }
    }
}
