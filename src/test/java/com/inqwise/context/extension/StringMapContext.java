package com.inqwise.context.extension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.inqwise.context.ContextMap;
import com.inqwise.context.ContextMapItem;
import com.inqwise.context.ContextMaps;
import com.inqwise.context.ContextSlot;
import com.inqwise.context.spi.ContextChainDataItem;
import com.inqwise.context.spi.ContextChainDataService;
import com.inqwise.context.spi.ContextItemFactory;
import io.vertx.core.json.JsonObject;

/** External-package example: map codecs and factories use only public APIs. */
public final class StringMapContext {
    /** Shared declaration with no captured context or mutable data. */
    public static final ContextMap<String, String> VALUES =
        ContextMaps.define("string_map", String.class, String.class);

    private StringMapContext() {
    }

    /** Application-owned, stateless codec for this particular map declaration. */
    public static final class Codec implements ContextChainDataService {
        private static final String VALUE = "string-map:1:json:";
        private static final String REMOVAL = "string-map:1:remove:";

        @Override
        public String key() {
            return VALUES.slot().key();
        }

        @Override
        public String marshalToString(ContextChainDataItem item) {
            JsonObject json = new JsonObject();
            VALUES.cast(item).snapshot().forEach(json::put);
            return VALUE + json.encode();
        }

        @Override
        public ContextMapItem<String, String> unmarshalFormString(String text) {
            if (!text.startsWith(VALUE)) {
                throw new IllegalArgumentException("Unsupported string-map envelope");
            }
            JsonObject json = new JsonObject(text.substring(VALUE.length()));
            Map<String, String> decoded = new LinkedHashMap<>();
            for (String key : json.fieldNames()) {
                if (!(json.getValue(key) instanceof String value)) {
                    throw new IllegalArgumentException("String-map values must be strings");
                }
                decoded.put(key, value);
            }
            return VALUES.item(decoded);
        }

        @Override
        public String marshalRemovalToString() {
            return REMOVAL;
        }

        @Override
        public boolean isRemoval(String text) {
            return REMOVAL.equals(text);
        }
    }

    /** Environment-specific initialization without changing the collection API. */
    public static final class Factory implements ContextItemFactory<ContextMapItem<String, String>> {
        /** Number of provider instances in the forked runtime. */
        public static final AtomicInteger constructed = new AtomicInteger();
        /** Number of explicit creations, excluding reads, imports, and removals. */
        public static final AtomicInteger created = new AtomicInteger();

        /** Creates the service provider. */
        public Factory() {
            constructed.incrementAndGet();
        }

        @Override
        public ContextSlot<ContextMapItem<String, String>> slot() {
            return VALUES.slot();
        }

        @Override
        public ContextMapItem<String, String> create() {
            created.incrementAndGet();
            return VALUES.item(Map.of("factory", "configured"));
        }
    }
}
