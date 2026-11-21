package com.inqwise.context;

import static com.inqwise.context.ContextChainStorageHolder.STORAGE_CONTEXT;
import static io.vertx.core.spi.context.storage.AccessMode.CONCURRENT;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.inqwise.context.spi.ContextChainDataItem;

import io.vertx.core.internal.ContextInternal;
/**
 * Helper to store data in the local context.
 */
final class DefaultStorage {
    private DefaultStorage() {
    }
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

    static <T extends ContextChainDataItem> void put(String key, T value, Class<T> type) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        Objects.requireNonNull(type);
        contextualDataMap(currentContext()).compute(key, (ignored, previous) -> {
            if (!isRemoved(previous)) {
                type.cast(previous);
            }
            return value;
        });
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
        ConcurrentMap<?, ?> values = Objects.requireNonNull(ctx).getLocal(STORAGE_CONTEXT, CONCURRENT);
        return values == null ? null : (ContextChainDataItem) values.get(key);
    }

    static ContextChainDataItem getOrCreate(String key, Supplier<? extends ContextChainDataItem> factory) {
        return getOrCreate(currentContext(), key, factory);
    }

    static ContextChainDataItem getOrCreate(ContextInternal context, String key,
            Supplier<? extends ContextChainDataItem> factory) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(factory);
        ContextChainDataItem value = contextualDataMap(context).computeIfAbsent(key,
            ignored -> Objects.requireNonNull(factory.get(), "Context item factory returned null"));
        if (isRemoved(value)) {
            throw new IllegalStateException("Context item was explicitly removed; use put to reintroduce it");
        }
        return value;
    }

    static <T extends ContextChainDataItem> T remove(String key, Class<T> type) {
        AtomicReference<T> previous = new AtomicReference<>();
        contextualDataMap(currentContext()).compute(key, (ignored, value) -> {
            previous.set(isRemoved(value) ? null : type.cast(value));
            return Removed.INSTANCE;
        });
        return previous.get();
    }

    static boolean isRemoved(ContextChainDataItem value) {
        return value == Removed.INSTANCE;
    }

    // Used only on new ingress contexts; live-flow removal must retain its marker.
    static void reset(ContextInternal context) {
        context.removeLocal(STORAGE_CONTEXT, CONCURRENT);
    }

    private static ContextInternal currentContext() {
        ContextInternal ctx = ContextInternal.current();
        if (ctx == null) {
            throw new IllegalStateException("Context storage requires a calling Vert.x context");
        }
        return ctx;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ConcurrentMap<String, ContextChainDataItem> contextualDataMap(ContextInternal ctx) {
        ConcurrentMap lcd = Objects.requireNonNull(ctx).getLocal(STORAGE_CONTEXT, CONCURRENT, ConcurrentHashMap::new);
        return (ConcurrentMap<String, ContextChainDataItem>) lcd;
    }
}
