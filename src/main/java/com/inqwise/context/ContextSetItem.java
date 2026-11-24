package com.inqwise.context;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import com.inqwise.context.spi.ContextChainDataItem;

/**
 * Immutable set snapshot for storage, codec, and factory providers. Construct
 * through {@link ContextSet#item(Collection)}. Elements remain the supplied
 * objects and need stable equality/hash codes and their own immutability or
 * thread safety. Empty snapshots retain the declared element type.
 * @param <E> the element type
 */
public final class ContextSetItem<E> implements ContextChainDataItem {
    private final Class<E> elementType;
    private final Set<E> snapshot;

    ContextSetItem(Class<E> elementType, Collection<? extends E> values) {
        this.elementType = elementType;
        Set<E> copy = new LinkedHashSet<>();
        values.forEach(value -> copy.add(Objects.requireNonNull(elementType.cast(value), "Set element is mandatory")));
        snapshot = Collections.unmodifiableSet(copy);
    }

    /** @return the declared element class, not inferred from membership */
    public Class<E> elementType() {
        return elementType;
    }

    /**
     * Returns the same read-only snapshot on every call. First encounter order
     * is retained and duplicate elements follow normal Set equality semantics.
     * @return the immutable membership snapshot, without deep-copying elements
     */
    public Set<E> snapshot() {
        return snapshot;
    }
}
