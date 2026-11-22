package com.inqwise.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.inqwise.context.spi.ContextChainDataItem;

/**
 * Immutable map snapshot for storage, codec, and factory providers. Instances
 * are constructed through {@link ContextMap#item(Map)}. Elements are retained
 * by reference; they must be immutable or independently thread-safe, and keys
 * must have stable equality and hash codes. Generic types are retained even
 * when the snapshot is empty.
 * @param <K> the key type
 * @param <V> the value type
 */
public final class ContextMapItem<K, V> implements ContextChainDataItem {
    private final Class<K> keyType;
    private final Class<V> valueType;
    private final Map<K, V> snapshot;

    ContextMapItem(Class<K> keyType, Class<V> valueType, Map<? extends K, ? extends V> values) {
        this.keyType = keyType;
        this.valueType = valueType;
        Map<K, V> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(Objects.requireNonNull(keyType.cast(key), "Map key is mandatory"),
            Objects.requireNonNull(valueType.cast(value), "Map value is mandatory")));
        snapshot = Collections.unmodifiableMap(copy);
    }

    /** @return the declared key class, not inferred from entries */
    public Class<K> keyType() {
        return keyType;
    }

    /** @return the declared value class, not inferred from entries */
    public Class<V> valueType() {
        return valueType;
    }

    /**
     * Returns the same immutable snapshot on every call. Input encounter order
     * is retained; codec authors choose canonical wire ordering if required.
     * @return the read-only map, whose elements are not deep-copied
     */
    public Map<K, V> snapshot() {
        return snapshot;
    }
}
