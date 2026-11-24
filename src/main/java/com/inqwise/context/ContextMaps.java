package com.inqwise.context;

/** Declares reusable typed context maps without capturing a flow or storing data. */
public final class ContextMaps {
    private ContextMaps() {
    }

    /**
     * Defines a map with explicit key/value types and an empty default factory.
     * This does not initialize storage or register a codec. Collection and other
     * parameterized element types require an application-owned wrapper rather
     * than guessing nested type arguments from erased class tokens.
     * @param key the stable storage/wire key, declared once
     * @param keyType the reference type of entry keys
     * @param valueType the reference type of entry values
     * @param <K> the entry key type
     * @param <V> the entry value type
     * @return the immutable context-bound handle
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the key is blank or an element type is primitive
     */
    public static <K, V> ContextMap<K, V> define(String key, Class<K> keyType, Class<V> valueType) {
        return new ContextMap<>(key, keyType, valueType);
    }
}
