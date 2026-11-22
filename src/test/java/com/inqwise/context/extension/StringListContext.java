package com.inqwise.context.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.inqwise.context.ContextList;
import com.inqwise.context.ContextListItem;
import com.inqwise.context.ContextLists;
import com.inqwise.context.ContextSlot;
import com.inqwise.context.spi.ContextChainDataItem;
import com.inqwise.context.spi.ContextChainDataService;
import com.inqwise.context.spi.ContextItemFactory;
import io.vertx.core.json.JsonArray;

/** External-package list example, preserving order and duplicates in its codec. */
public final class StringListContext {
    /** Shared declaration, not a captured flow or mutable list. */
    public static final ContextList<String> STEPS = ContextLists.define("string_list", String.class);

    private StringListContext() {
    }

    /** Stateless application-owned versioned codec with whole-item removal. */
    public static final class Codec implements ContextChainDataService {
        private static final String VALUE = "string-list:1:json:";
        private static final String REMOVAL = "string-list:1:remove:";

        @Override
        public String key() {
            return STEPS.slot().key();
        }

        @Override
        public String marshalToString(ContextChainDataItem item) {
            return VALUE + new JsonArray(new ArrayList<>(STEPS.cast(item).snapshot())).encode();
        }

        @Override
        public ContextListItem<String> unmarshalFormString(String text) {
            if (!text.startsWith(VALUE)) {
                throw new IllegalArgumentException("Unsupported string-list envelope");
            }
            List<String> values = new ArrayList<>();
            for (Object encoded : new JsonArray(text.substring(VALUE.length()))) {
                if (!(encoded instanceof String value)) {
                    throw new IllegalArgumentException("String-list elements must be strings");
                }
                values.add(value);
            }
            return STEPS.item(values);
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

    /** Environment-specific initialization independent of codec discovery. */
    public static final class Factory implements ContextItemFactory<ContextListItem<String>> {
        /** Number of provider instances in the forked runtime. */
        public static final AtomicInteger constructed = new AtomicInteger();
        /** Number of explicit creations. */
        public static final AtomicInteger created = new AtomicInteger();

        /** Creates the service provider. */
        public Factory() {
            constructed.incrementAndGet();
        }

        @Override
        public ContextSlot<ContextListItem<String>> slot() {
            return STEPS.slot();
        }

        @Override
        public ContextListItem<String> create() {
            created.incrementAndGet();
            return STEPS.item(List.of("configured"));
        }
    }
}
