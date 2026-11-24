package com.inqwise.context;

/** Declares typed context sets without capturing a flow or storing values. */
public final class ContextSets {
    private ContextSets() {
    }

    /**
     * Defines a set with an empty fallback factory. This does not initialize
     * storage or register a codec. Nested generic arguments cannot be inferred
     * from erased class tokens; use an application-owned element wrapper when
     * stronger nested validation is needed.
     * @param key the stable storage/wire key
     * @param elementType the reference type of set elements
     * @param <E> the element type
     * @return the immutable context-bound handle
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if key is blank or elementType is primitive
     */
    public static <E> ContextSet<E> define(String key, Class<E> elementType) {
        return new ContextSet<>(key, elementType);
    }
}
