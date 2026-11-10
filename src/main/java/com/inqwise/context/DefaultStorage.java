package com.inqwise.context;

import static com.inqwise.context.ContextChainStorageHolder.STORAGE_CONTEXT;
import static io.vertx.core.spi.context.storage.AccessMode.CONCURRENT;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.inqwise.context.spi.ContextChainDataItem;

import io.vertx.core.internal.ContextInternal;
/**
 * Helper to store data in the local context.
 */
class DefaultStorage {
    private enum Removed implements ContextChainDataItem {
        INSTANCE
    }

    static void put(String key, ContextChainDataItem value) {
        put(key, value, true);
    }

    static void put(String key, ContextChainDataItem value, boolean replaceExisting) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        put(currentContext(), key, value, replaceExisting);
    }

    static void put(ContextInternal ctx, String key, ContextChainDataItem value, boolean replaceExisting) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        if (replaceExisting) {
            contextualDataMap(ctx).put(key, value);
        } else {
            contextualDataMap(ctx).putIfAbsent(key, value);
        }
    }

    static ContextChainDataItem get(String key) {
        Objects.requireNonNull(key);
        return get(currentContext(), key);
    }

    static ContextChainDataItem get(ContextInternal ctx, String key) {
        ContextChainDataItem value = getEntry(ctx, key);
        return isRemoved(value) ? null : value;
    }

    static ContextChainDataItem remove(String key) {
        Objects.requireNonNull(key);
        ContextChainDataItem previous = contextualDataMap(currentContext()).put(key, Removed.INSTANCE);
        return isRemoved(previous) ? null : previous;
    }

    static void remove(ContextInternal ctx, String key, boolean replaceExisting) {
        put(ctx, key, Removed.INSTANCE, replaceExisting);
    }

    // Include deletion markers when deciding whether to import or transport an entry.
    static ContextChainDataItem getEntry(ContextInternal ctx, String key) {
        Objects.requireNonNull(key);
        return contextualDataMap(ctx).get(key);
    }

    static boolean isRemoved(ContextChainDataItem value) {
        return value == Removed.INSTANCE;
    }

    private static ContextInternal currentContext() {
        ContextInternal ctx = ContextInternal.current();
        if (ctx == null) {
            throw new IllegalStateException("Context storage requires a calling Vert.x context");
        }
        return ctx;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private final static ConcurrentMap<String, ContextChainDataItem> contextualDataMap(ContextInternal ctx) {
        ConcurrentMap lcd = Objects.requireNonNull(ctx).getLocal(STORAGE_CONTEXT, CONCURRENT, ConcurrentHashMap::new);
        return (ConcurrentMap<String, ContextChainDataItem>) lcd;
    }
}
