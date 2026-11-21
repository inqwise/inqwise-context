package com.inqwise.context;

import java.util.Objects;
import java.util.function.Supplier;

import com.inqwise.context.spi.ContextChainDataItem;

/**
 * Immutable typed handle to one context item, not a captured context or stored
 * value. Slot declarations may be shared; operations resolve the active context.
 *
 * @param <T> the stored item type
 */
public final class ContextSlot<T extends ContextChainDataItem> {
    private final String key;
    private final Class<T> type;
    private final Supplier<? extends T> defaultFactory;

    ContextSlot(String key, Class<T> type, Supplier<? extends T> defaultFactory) {
        this.key = Objects.requireNonNull(key, "Context item key is mandatory");
        if (key.isBlank()) {
            throw new IllegalArgumentException("Context item key must not be blank");
        }
        this.type = Objects.requireNonNull(type, "Context item type is mandatory");
        if (!ContextChainDataItem.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("Context item type must implement ContextChainDataItem");
        }
        this.defaultFactory = defaultFactory;
    }

    /**
     * Returns the stable identifier used by codec and configuration providers.
     *
     * @return the storage and wire key
     */
    public String key() {
        return key;
    }

    /**
     * Returns the declared item type used to validate stored and created values.
     *
     * @return the item class
     */
    public Class<T> type() {
        return type;
    }

    /**
     * Reads without creating a value or resolving factory providers.
     *
     * @return the exact stored instance, or null if absent or removed
     * @throws IllegalStateException if called outside a Vert.x context
     * @throws ClassCastException if the key contains another item type
     */
    public T get() {
        return type.cast(DefaultStorage.get(key));
    }

    /**
     * Requires an existing item without invoking a factory.
     *
     * @return the exact stored instance
     * @throws IllegalStateException if no value exists or no context is active
     * @throws ClassCastException if the key contains another item type
     */
    public T require() {
        T value = get();
        if (value == null) {
            throw new IllegalStateException("Required context item is absent");
        }
        return value;
    }

    /**
     * Stores a value or explicitly replaces a removal marker.
     *
     * @param value the item to store
     * @throws NullPointerException if value is null
     * @throws IllegalStateException if no context is active
     * @throws ClassCastException if a raw caller supplies another item type or
     *         an existing value is incompatible with this declaration
     */
    public void put(T value) {
        DefaultStorage.put(key, type.cast(Objects.requireNonNull(value, "Context item is mandatory")), type);
    }

    /**
     * Removes the item and retains its transportable removal marker.
     *
     * @return the previous instance, or null if absent or already removed
     * @throws IllegalStateException if no context is active
     * @throws ClassCastException if the key contains another item type
     */
    public T remove() {
        return DefaultStorage.remove(key, type);
    }

    /**
     * Returns the existing item or atomically creates it using an SPI factory,
     * falling back to this declaration's default factory. Providers are resolved
     * only when creation is needed. An explicit removal is never revived.
     * Factories must be fast and must not modify context storage or initialize
     * other slots while this atomic construction callback runs.
     *
     * @return the stored instance, including the result of successful creation
     * @throws IllegalStateException if no context or factory exists, the item was
     *         removed, or factory-provider configuration is invalid
     * @throws NullPointerException if the factory returns null
     * @throws ClassCastException if an existing or created value has the wrong type
     */
    public T getOrCreate() {
        return getOrCreate(() -> ContextItemFactories.factory(this, defaultFactory).get());
    }

    /**
     * Returns an existing item or atomically creates one with an explicit
     * factory, bypassing SPI discovery. Failed creation leaves no new entry.
     * Existing values and removal markers do not invoke the factory.
     *
     * @param factory the construction function, invoked on the calling context
     * @return the stored instance
     * @throws NullPointerException if factory or its result is null
     * @throws IllegalStateException if no context is active or the item was removed
     * @throws ClassCastException if an existing or created value has the wrong type
     */
    public T getOrCreate(Supplier<? extends T> factory) {
        Objects.requireNonNull(factory, "Context item factory is mandatory");
        return type.cast(DefaultStorage.getOrCreate(key, () -> type.cast(factory.get())));
    }
}
