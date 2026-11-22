package com.inqwise.context;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.inqwise.context.spi.ContextChainDataItem;

/**
 * Immutable list snapshot for storage, codec, and factory providers. Construct
 * through {@link ContextList#item(Collection)}. Order and duplicates are retained,
 * along with the declared element type even when empty. Elements themselves are
 * not copied and require immutability or independent thread safety.
 * @param <E> the element type
 */
public final class ContextListItem<E> implements ContextChainDataItem {
    private final Class<E> elementType;
    private final List<E> snapshot;

    ContextListItem(Class<E> elementType, Collection<? extends E> values) {
        this.elementType = elementType;
        List<E> copy = new ArrayList<>();
        values.forEach(value -> copy.add(Objects.requireNonNull(elementType.cast(value), "List element is mandatory")));
        snapshot = Collections.unmodifiableList(copy);
    }

    /** @return the declared element class, not inferred from current contents */
    public Class<E> elementType() {
        return elementType;
    }

    /**
     * Returns the same immutable snapshot on each call, preserving the input's
     * encounter order and duplicate positions.
     * @return the read-only list, without deep-copying its elements
     */
    public List<E> snapshot() {
        return snapshot;
    }
}
