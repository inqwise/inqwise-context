package com.inqwise.context.extension;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.inqwise.context.ContextSet;
import com.inqwise.context.ContextSetItem;
import com.inqwise.context.ContextSets;
import com.inqwise.context.ContextSlot;
import com.inqwise.context.spi.ContextChainDataItem;
import com.inqwise.context.spi.ContextChainDataService;
import com.inqwise.context.spi.ContextItemFactory;
import io.vertx.core.json.JsonArray;

/** External-package example with an application-owned set codec and factory. */
public final class StringSetContext {
    /** Shared declaration, not a captured flow or stored value. */
    public static final ContextSet<String> LABELS = ContextSets.define("string_set", String.class);

    private StringSetContext() {
    }

    /** Stateless versioned codec; this example rejects duplicate wire elements. */
    public static final class Codec implements ContextChainDataService {
        private static final String VALUE = "string-set:1:json:";
        private static final String REMOVAL = "string-set:1:remove:";

        @Override
        public String key() {
            return LABELS.slot().key();
        }

        @Override
        public String marshalToString(ContextChainDataItem item) {
            return VALUE + new JsonArray(new ArrayList<>(LABELS.cast(item).snapshot())).encode();
        }

        @Override
        public ContextSetItem<String> unmarshalFormString(String text) {
            if (!text.startsWith(VALUE)) {
                throw new IllegalArgumentException("Unsupported string-set envelope");
            }
            Set<String> values = new LinkedHashSet<>();
            for (Object encoded : new JsonArray(text.substring(VALUE.length()))) {
                if (!(encoded instanceof String value) || !values.add(value)) {
                    throw new IllegalArgumentException("Invalid or duplicate string-set element");
                }
            }
            return LABELS.item(values);
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

    /** Environment-specific initialization, independent of the codec. */
    public static final class Factory implements ContextItemFactory<ContextSetItem<String>> {
        /** Number of provider instances in the forked runtime. */
        public static final AtomicInteger constructed = new AtomicInteger();
        /** Number of explicit creations. */
        public static final AtomicInteger created = new AtomicInteger();

        /** Creates the service provider. */
        public Factory() {
            constructed.incrementAndGet();
        }

        @Override
        public ContextSlot<ContextSetItem<String>> slot() {
            return LABELS.slot();
        }

        @Override
        public ContextSetItem<String> create() {
            created.incrementAndGet();
            return LABELS.item(Set.of("configured"));
        }
    }
}
