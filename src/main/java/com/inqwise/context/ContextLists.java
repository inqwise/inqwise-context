package com.inqwise.context;

/** Declares typed context lists without capturing a flow or storing data. */
public final class ContextLists {
    private ContextLists() {
    }

    /**
     * Defines a list with an empty fallback factory. This does not initialize
     * storage or register a codec. Nested generic arguments cannot be inferred
     * from class tokens; use an application-owned element wrapper when stronger
     * nested validation is needed.
     * @param key the stable storage/wire key
     * @param elementType the reference type of list elements
     * @param <E> the element type
     * @return the immutable context-bound handle
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if key is blank or elementType is primitive
     */
    public static <E> ContextList<E> define(String key, Class<E> elementType) {
        return new ContextList<>(key, elementType);
    }
}
