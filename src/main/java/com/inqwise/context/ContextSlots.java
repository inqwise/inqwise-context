package com.inqwise.context;

import java.util.Objects;
import java.util.function.Supplier;

import com.inqwise.context.spi.ContextChainDataItem;

/** Creates typed declarations without registering providers or capturing contexts. */
public final class ContextSlots {
    private ContextSlots() {
    }

    /**
     * Declares an item without a default factory. A codec references the same
     * key; declaring a slot does not enable transport or discover its codec.
     *
     * @param key the stable non-blank storage and wire identifier
     * @param type the item class
     * @param <T> the item type
     * @return an immutable context-independent handle
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if key is blank
     */
    public static <T extends ContextChainDataItem> ContextSlot<T> define(String key, Class<T> type) {
        return new ContextSlot<>(key, type, null);
    }

    /**
     * Declares an item with a shared, concurrency-safe construction function.
     * Sharing the factory must not mean sharing mutable values between flows.
     *
     * @param key the stable non-blank storage and wire identifier
     * @param type the item class
     * @param factory the default factory, used only for explicit creation
     * @param <T> the item type
     * @return an immutable context-independent handle
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if key is blank
     */
    public static <T extends ContextChainDataItem> ContextSlot<T> define(String key, Class<T> type,
            Supplier<? extends T> factory) {
        return new ContextSlot<>(key, type, Objects.requireNonNull(factory, "Default factory is mandatory"));
    }
}
