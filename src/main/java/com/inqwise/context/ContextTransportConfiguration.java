package com.inqwise.context;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable transport configuration containing metadata keys, never request
 * values or captured contexts. An adapter must be installed separately to
 * enable network propagation.
 */
public final class ContextTransportConfiguration {
    private final Set<String> includedKeys;

    private ContextTransportConfiguration(Builder builder) {
        includedKeys = Collections.unmodifiableSet(new LinkedHashSet<>(builder.keys));
    }

    /**
     * Creates a builder with an empty allowlist. An empty configuration exports
     * and imports no metadata. Keys retain their storage spelling.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the immutable allowlist, in insertion order.
     *
     * @return the selected storage keys
     */
    public Set<String> includedKeys() {
        return includedKeys;
    }

    /** Builds immutable snapshots; builders themselves are not thread-safe. */
    public static final class Builder {
        private final Set<String> keys = new LinkedHashSet<>();

        private Builder() {
        }

        /**
         * Allows a metadata key. Repeating the same key has no effect.
         * This does not load its codec provider or read context storage.
         *
         * @param key the storage key
         * @return this builder
         * @throws NullPointerException if the key is null
         * @throws IllegalArgumentException if the key is blank
         */
        public Builder include(String key) {
            Objects.requireNonNull(key, "Metadata key is mandatory");
            if (key.isBlank()) {
                throw new IllegalArgumentException("Metadata key must not be blank");
            }
            keys.add(key);
            return this;
        }

        /**
         * Allows a typed declaration without repeating its wire key. This does
         * not read storage, resolve factories, or enable transport by itself.
         *
         * @param slot the item declaration
         * @return this builder
         * @throws NullPointerException if slot is null
         */
        public Builder include(ContextSlot<?> slot) {
            return include(Objects.requireNonNull(slot, "Context slot is mandatory").key());
        }

        /**
         * Creates a configuration independent of subsequent builder changes.
         *
         * @return an immutable configuration
         */
        public ContextTransportConfiguration build() {
            return new ContextTransportConfiguration(this);
        }
    }
}
