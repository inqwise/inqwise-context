package com.inqwise.context;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import com.inqwise.context.spi.ContextChainDataItem;

/**
 * Shared typed handle to a map in the active Vert.x context, not a mutable
 * {@link Map} implementation. Entry operations require initialization. Each
 * mutation atomically publishes a complete immutable snapshot; previous
 * snapshots remain unchanged. Operations are linearizable for one context item,
 * not distributed transactions. Snapshot copying is proportional to map size.
 * @param <K> the entry key type
 * @param <V> the entry value type
 */
public final class ContextMap<K, V> {
    private record Parameters(Class<?> keyType, Class<?> valueType) {
    }

    private final Class<K> keyType;
    private final Class<V> valueType;
    private final ContextSlot<ContextMapItem<K, V>> slot;

    ContextMap(String key, Class<K> keyType, Class<V> valueType) {
        this.keyType = Objects.requireNonNull(keyType, "Map key type is mandatory");
        this.valueType = Objects.requireNonNull(valueType, "Map value type is mandatory");
        if (keyType.isPrimitive() || valueType.isPrimitive()) {
            throw new IllegalArgumentException("Context map element types must be reference types");
        }
        slot = new ContextSlot<>(key, itemClass(), () -> item(Map.of()),
            new Parameters(keyType, valueType), this::cast);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <K, V> Class<ContextMapItem<K, V>> itemClass() {
        // The slot validator enforces the parameter types missing from this class token.
        return (Class) ContextMapItem.class;
    }

    /**
     * Returns the parameter-validated declaration for codec/configuration and
     * factory providers. Its class token alone does not encode generic types.
     * @return the shared declaration, not a captured map
     */
    public ContextSlot<ContextMapItem<K, V>> slot() {
        return slot;
    }

    /**
     * Builds a defensive, parameter-validated snapshot without requiring a context.
     * Use this method from codec decoders and item factory providers. Input maps
     * must not be concurrently modified while copying.
     * @param values the complete map contents
     * @return a new immutable stored item
     * @throws NullPointerException if the map, a key, or a value is null
     * @throws ClassCastException if a raw caller supplies incompatible elements
     */
    public ContextMapItem<K, V> item(Map<? extends K, ? extends V> values) {
        return new ContextMapItem<>(keyType, valueType, Objects.requireNonNull(values, "Map is mandatory"));
    }

    /**
     * Validates a codec input against this declaration, including empty maps.
     * @param item the value to encode
     * @return the exact validated item
     * @throws NullPointerException if item is null
     * @throws ClassCastException if the item or its declared parameters differ
     */
    @SuppressWarnings("unchecked")
    public ContextMapItem<K, V> cast(ContextChainDataItem item) {
        Objects.requireNonNull(item, "Map item is mandatory");
        if (!(item instanceof ContextMapItem<?, ?> value)
                || value.keyType() != keyType || value.valueType() != valueType) {
            throw new ClassCastException("Context map item does not match declared key/value types");
        }
        return (ContextMapItem<K, V>) value;
    }

    /**
     * Explicitly initializes absent storage using SPI or the empty default.
     * Existing values are retained; explicit removal is never revived.
     * @return the existing or created immutable snapshot
     * @throws IllegalStateException if outside a context, removed, or SPI configuration is invalid
     * @throws ClassCastException if stored or created parameter types conflict
     */
    public Map<K, V> getOrCreate() {
        return slot.getOrCreate().snapshot();
    }

    /**
     * Initializes using an explicit factory, bypassing SPI. The factory must
     * follow the synchronous, non-mutating callback contract of ContextSlot.
     * @param factory the complete initial contents, evaluated only if absent
     * @return the existing or created immutable snapshot
     * @throws NullPointerException if the factory, result, or entries are null
     * @throws IllegalStateException if outside a context or explicitly removed
     * @throws ClassCastException if existing or created types conflict
     */
    public Map<K, V> getOrCreate(Supplier<? extends Map<K, V>> factory) {
        Objects.requireNonNull(factory, "Map factory is mandatory");
        return slot.getOrCreate(() -> item(factory.get())).snapshot();
    }

    /**
     * Requires an existing map and returns its read-only snapshot.
     * @return the exact stored snapshot
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if the existing declaration conflicts
     */
    public Map<K, V> snapshot() {
        return slot.require().snapshot();
    }

    /**
     * Reads an entry from an initialized map.
     * @param key the non-null entry key
     * @return the value, or null if the entry is missing
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws NullPointerException if key is null
     * @throws ClassCastException if key or stored types conflict
     */
    public V get(K key) {
        return snapshot().get(checkedKey(key));
    }

    /**
     * Atomically adds or replaces one entry in an initialized map.
     * @param key the entry key
     * @param value the entry value
     * @return the previous value, or null if missing
     * @throws NullPointerException if key or value is null
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if supplied or stored types conflict
     */
    public V put(K key, V value) {
        K checked = checkedKey(key);
        V replacement = checkedValue(value);
        return mutate(values -> values.put(checked, replacement));
    }

    /**
     * Atomically inserts an entry only when missing from an initialized map.
     * @param key the entry key
     * @param value the entry value
     * @return the previous value, or null if inserted
     * @throws NullPointerException if key or value is null
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if supplied or stored types conflict
     */
    public V putIfAbsent(K key, V value) {
        K checked = checkedKey(key);
        V replacement = checkedValue(value);
        return mutate(values -> values.putIfAbsent(checked, replacement));
    }

    /**
     * Atomically removes one entry without removing the context item.
     * @param key the entry key
     * @return the previous value, or null if missing
     * @throws NullPointerException if key is null
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if supplied or stored types conflict
     */
    public V remove(K key) {
        K checked = checkedKey(key);
        return mutate(values -> values.remove(checked));
    }

    /**
     * Atomically adds/replaces a batch in an initialized map. Invalid inputs
     * cannot partially apply the batch.
     * @param values the entries to merge
     * @throws NullPointerException if the map or any entry is null
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if supplied or stored types conflict
     */
    public void putAll(Map<? extends K, ? extends V> values) {
        Map<K, V> additions = item(values).snapshot();
        mutate(current -> {
            current.putAll(additions);
            return null;
        });
    }

    /**
     * Atomically clears an initialized map, retaining a present empty item.
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if the stored declaration conflicts
     */
    public void clear() {
        mutate(current -> {
            current.clear();
            return null;
        });
    }

    /**
     * Explicitly sets the complete map, initializing or reintroducing it.
     * This is replacement, not an entry merge; supplied maps are copied.
     * @param values the complete contents
     * @throws NullPointerException if the map or any entry is null
     * @throws IllegalStateException if outside a context
     * @throws ClassCastException if supplied or existing types conflict
     */
    public void replace(Map<? extends K, ? extends V> values) {
        slot.put(item(values));
    }

    /**
     * Removes the whole context item and retains its transportable marker.
     * Unlike {@link #remove(Object)}, this does not remove just one entry.
     * @return the previous immutable snapshot, or null if absent/already removed
     * @throws IllegalStateException if outside a context
     * @throws ClassCastException if the existing declaration conflicts
     */
    public Map<K, V> remove() {
        ContextMapItem<K, V> previous = slot.remove();
        return previous == null ? null : previous.snapshot();
    }

    /**
     * Atomically replaces the complete contents of an initialized map. The
     * callback receives a read-only snapshot and must return non-null contents;
     * returned maps are copied. It must be short, synchronous, and must not
     * modify context storage. Exceptions retain the old reference, but cannot
     * undo application mutations to mutable elements. No factory is invoked.
     * @param updater the whole-map transformation
     * @return the exact stored replacement snapshot
     * @throws NullPointerException if updater, its result, or any entry is null
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if returned or stored types conflict
     */
    public Map<K, V> update(UnaryOperator<Map<K, V>> updater) {
        Objects.requireNonNull(updater, "Map updater is mandatory");
        return slot.update(current -> item(updater.apply(current.snapshot()))).snapshot();
    }

    private <R> R mutate(Function<Map<K, V>, R> operation) {
        AtomicReference<R> result = new AtomicReference<>();
        slot.update(current -> {
            Map<K, V> copy = new LinkedHashMap<>(current.snapshot());
            result.set(operation.apply(copy));
            return item(copy);
        });
        return result.get();
    }

    private K checkedKey(Object key) {
        return Objects.requireNonNull(keyType.cast(key), "Map key is mandatory");
    }

    private V checkedValue(Object value) {
        return Objects.requireNonNull(valueType.cast(value), "Map value is mandatory");
    }
}
