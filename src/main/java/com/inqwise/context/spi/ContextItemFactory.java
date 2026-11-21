package com.inqwise.context.spi;

import com.inqwise.context.ContextSlot;

/**
 * Supplies an environment-specific factory for one typed slot. Register providers
 * through ServiceLoader using this interface's name. Providers and registrations
 * must be visible to the library's defining classloader and support concurrent
 * use. At most one factory may be registered per exact slot key.
 *
 * @param <T> the item type
 */
public interface ContextItemFactory<T extends ContextChainDataItem> {
    /**
     * Returns the declaration whose default factory this provider overrides.
     *
     * @return the non-null slot, with the same key and type used by callers
     */
    ContextSlot<T> slot();

    /**
     * Constructs an initial value on the caller's active context. Do not modify
     * context storage or initialize other slots in this callback. Throwing or
     * returning null stores no value.
     *
     * @return the new non-null item
     */
    T create();
}
