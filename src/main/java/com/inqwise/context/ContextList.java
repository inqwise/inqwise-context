package com.inqwise.context;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import com.inqwise.context.spi.ContextChainDataItem;

/**
 * Shared typed handle to an ordered list in the active Vert.x context, not a
 * mutable {@link List} implementation. Mutations publish complete immutable
 * snapshots with duplicates preserved. Index validation and mutation happen
 * atomically against the same current snapshot; indexes are not stable IDs.
 * Copying costs scale with list size and there are no distributed transactions.
 * @param <E> the element type
 */
public final class ContextList<E> {
    private record Parameters(Class<?> elementType) {
    }

    private final Class<E> elementType;
    private final ContextSlot<ContextListItem<E>> slot;

    ContextList(String key, Class<E> elementType) {
        this.elementType = Objects.requireNonNull(elementType, "List element type is mandatory");
        if (elementType.isPrimitive()) {
            throw new IllegalArgumentException("Context list element type must be a reference type");
        }
        slot = new ContextSlot<>(key, itemClass(), () -> item(List.of()), new Parameters(elementType), this::cast);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <E> Class<ContextListItem<E>> itemClass() {
        // The validator supplies the element type missing from the erased class token.
        return (Class) ContextListItem.class;
    }

    /** @return the parameter-validated declaration for codecs, factories, and configuration */
    public ContextSlot<ContextListItem<E>> slot() {
        return slot;
    }

    /**
     * Builds a defensive snapshot without requiring a context. Inputs must not
     * be concurrently modified during copying. Order and duplicates are retained.
     * @param values the complete list contents
     * @return a new immutable item with the declared element type
     * @throws NullPointerException if collection or elements are null
     * @throws ClassCastException if a raw caller supplies incompatible elements
     */
    public ContextListItem<E> item(Collection<? extends E> values) {
        return new ContextListItem<>(elementType, Objects.requireNonNull(values, "List contents are mandatory"));
    }

    /**
     * Validates a codec input, including empty lists, without requiring a context.
     * @param item the value to encode
     * @return the exact validated item
     * @throws NullPointerException if item is null
     * @throws ClassCastException if item or declared element type conflicts
     */
    @SuppressWarnings("unchecked")
    public ContextListItem<E> cast(ContextChainDataItem item) {
        Objects.requireNonNull(item, "List item is mandatory");
        if (!(item instanceof ContextListItem<?> value) || value.elementType() != elementType) {
            throw new ClassCastException("Context list item does not match declared element type");
        }
        return (ContextListItem<E>) value;
    }

    /**
     * Explicitly initializes absent storage using SPI or the empty fallback.
     * Existing values remain unchanged; explicit removal is never revived.
     * @return the existing or created immutable snapshot
     * @throws IllegalStateException if outside a context, removed, or SPI configuration is invalid
     * @throws NullPointerException if a provider returns null
     * @throws ClassCastException if stored or created types conflict
     */
    public List<E> getOrCreate() {
        return slot.getOrCreate().snapshot();
    }

    /**
     * Initializes with an explicit factory, bypassing SPI. Its synchronous
     * callback must not modify context storage. Returned contents are copied.
     * @param factory the complete initial contents, evaluated only if absent
     * @return the existing or created immutable snapshot
     * @throws NullPointerException if factory, result, or elements are null
     * @throws IllegalStateException if outside a context or explicitly removed
     * @throws ClassCastException if stored or created types conflict
     */
    public List<E> getOrCreate(Supplier<? extends List<E>> factory) {
        Objects.requireNonNull(factory, "List factory is mandatory");
        return slot.getOrCreate(() -> item(factory.get())).snapshot();
    }

    /**
     * Requires the current list without initializing it.
     * @return the exact immutable snapshot
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if the stored declaration conflicts
     */
    public List<E> snapshot() {
        return slot.require().snapshot();
    }

    /**
     * Reads a position from the current snapshot of an initialized list.
     * @param index the zero-based position
     * @return the stored element at that position
     * @throws IndexOutOfBoundsException if index is outside the snapshot
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if stored types conflict
     */
    public E get(int index) {
        return snapshot().get(index);
    }

    /**
     * Atomically appends an element to an initialized list.
     * @param value the element to append, including an equal duplicate
     * @return its index at this operation's atomic publication, not a stable ID
     * @throws NullPointerException if value is null
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if supplied or stored types conflict
     */
    public int append(E value) {
        E checked = checkedElement(value);
        return mutate(current -> {
            int index = current.size();
            current.add(checked);
            return index;
        });
    }

    /**
     * Atomically inserts an element, shifting later positions to the right.
     * @param index the insertion position, including the current size
     * @param value the element to insert
     * @throws IndexOutOfBoundsException if index is negative or greater than current size
     * @throws NullPointerException if value is null
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if supplied or stored types conflict
     */
    public void insert(int index, E value) {
        E checked = checkedElement(value);
        mutate(current -> {
            current.add(index, checked);
            return null;
        });
    }

    /**
     * Atomically replaces one position in an initialized list.
     * @param index the zero-based position
     * @param value the replacement element
     * @return the previous element
     * @throws IndexOutOfBoundsException if index is outside the current list
     * @throws NullPointerException if value is null
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if supplied or stored types conflict
     */
    public E set(int index, E value) {
        E checked = checkedElement(value);
        return mutate(current -> current.set(index, checked));
    }

    /**
     * Atomically removes one position and shifts later positions to the left.
     * @param index the zero-based position
     * @return the removed element, without removing the context item
     * @throws IndexOutOfBoundsException if index is outside the current list
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if stored types conflict
     */
    public E removeAt(int index) {
        return mutate(current -> current.remove(index));
    }

    /**
     * Atomically appends a validated batch in encounter order, retaining duplicates.
     * @param values the elements to append
     * @throws NullPointerException if collection or elements are null
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if supplied or stored types conflict
     */
    public void appendAll(Collection<? extends E> values) {
        List<E> additions = item(values).snapshot();
        mutate(current -> {
            current.addAll(additions);
            return null;
        });
    }

    /**
     * Atomically inserts a validated batch as a contiguous range in encounter order.
     * @param index the insertion position, including current size
     * @param values the elements to insert, including duplicates
     * @throws IndexOutOfBoundsException if index is negative or greater than current size
     * @throws NullPointerException if collection or elements are null
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if supplied or stored types conflict
     */
    public void insertAll(int index, Collection<? extends E> values) {
        List<E> additions = item(values).snapshot();
        mutate(current -> {
            current.addAll(index, additions);
            return null;
        });
    }

    /**
     * Clears an initialized list, retaining a present, typed empty item.
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
     * Explicitly replaces complete contents, initializing or reintroducing the list.
     * @param values the complete contents, copied before publication
     * @throws NullPointerException if collection or elements are null
     * @throws IllegalStateException if outside a context
     * @throws ClassCastException if supplied or existing types conflict
     */
    public void replace(Collection<? extends E> values) {
        slot.put(item(values));
    }

    /**
     * Removes the entire context item and retains its transportable removal marker.
     * Unlike {@link #removeAt(int)}, this removes the item rather than one position.
     * @return the previous immutable snapshot, or null if absent/already removed
     * @throws IllegalStateException if outside a context
     * @throws ClassCastException if the stored declaration conflicts
     */
    public List<E> remove() {
        ContextListItem<E> previous = slot.remove();
        return previous == null ? null : previous.snapshot();
    }

    /**
     * Atomically transforms an initialized read-only snapshot into complete
     * replacement contents. Results are copied. The callback must be short,
     * synchronous, and must not modify context storage. Exceptions preserve
     * the old reference but cannot undo application mutations to element fields.
     * @param updater the whole-list transformation, returning a non-null list
     * @return the exact stored immutable replacement
     * @throws NullPointerException if updater, result, or elements are null
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if stored or returned types conflict
     */
    public List<E> update(UnaryOperator<List<E>> updater) {
        Objects.requireNonNull(updater, "List updater is mandatory");
        return slot.update(current -> item(updater.apply(current.snapshot()))).snapshot();
    }

    private <R> R mutate(Function<List<E>, R> operation) {
        AtomicReference<R> result = new AtomicReference<>();
        slot.update(current -> {
            List<E> copy = new ArrayList<>(current.snapshot());
            result.set(operation.apply(copy));
            return item(copy);
        });
        return result.get();
    }

    private E checkedElement(Object value) {
        return Objects.requireNonNull(elementType.cast(value), "List element is mandatory");
    }
}
