package com.inqwise.context;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import com.inqwise.context.spi.ContextChainDataItem;

/**
 * Shared typed handle to a set in the active Vert.x context, not a mutable
 * {@link Set} implementation. Membership operations require initialization and
 * atomically publish immutable snapshots. Copying is proportional to set size;
 * these operations are not cross-context or distributed transactions.
 * @param <E> the element type
 */
public final class ContextSet<E> {
    private record Parameters(Class<?> elementType) {
    }

    private final Class<E> elementType;
    private final ContextSlot<ContextSetItem<E>> slot;

    ContextSet(String key, Class<E> elementType) {
        this.elementType = Objects.requireNonNull(elementType, "Set element type is mandatory");
        if (elementType.isPrimitive()) {
            throw new IllegalArgumentException("Context set element type must be a reference type");
        }
        slot = new ContextSlot<>(key, itemClass(), () -> item(Set.of()), new Parameters(elementType), this::cast);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <E> Class<ContextSetItem<E>> itemClass() {
        // The validator supplies the element type missing from the erased class token.
        return (Class) ContextSetItem.class;
    }

    /** @return the parameter-validated declaration for codecs, factories, and configuration */
    public ContextSlot<ContextSetItem<E>> slot() {
        return slot;
    }

    /**
     * Builds a defensive snapshot without an active context. Inputs must not
     * be concurrently modified while copying. Duplicates are collapsed by equality.
     * @param values the complete set contents
     * @return a new immutable item with the declared element type
     * @throws NullPointerException if the collection or any element is null
     * @throws ClassCastException if a raw caller supplies incompatible elements
     */
    public ContextSetItem<E> item(Collection<? extends E> values) {
        return new ContextSetItem<>(elementType, Objects.requireNonNull(values, "Set contents are mandatory"));
    }

    /**
     * Validates a codec input, including empty snapshots, without a context.
     * @param item the value to encode
     * @return the exact validated item
     * @throws NullPointerException if item is null
     * @throws ClassCastException if the item or declared element type conflicts
     */
    @SuppressWarnings("unchecked")
    public ContextSetItem<E> cast(ContextChainDataItem item) {
        Objects.requireNonNull(item, "Set item is mandatory");
        if (!(item instanceof ContextSetItem<?> value) || value.elementType() != elementType) {
            throw new ClassCastException("Context set item does not match declared element type");
        }
        return (ContextSetItem<E>) value;
    }

    /**
     * Explicitly initializes absent storage using SPI or the empty fallback.
     * Existing values remain unchanged and removals are never revived.
     * @return the existing or created immutable snapshot
     * @throws IllegalStateException if outside a context, removed, or SPI configuration is invalid
     * @throws NullPointerException if a provider returns null
     * @throws ClassCastException if stored or created types conflict
     */
    public Set<E> getOrCreate() {
        return slot.getOrCreate().snapshot();
    }

    /**
     * Initializes through an explicit factory, bypassing SPI. Its synchronous
     * callback must not modify context storage. Supplied contents are copied.
     * @param factory the complete initial contents, used only if absent
     * @return the existing or created immutable snapshot
     * @throws NullPointerException if factory, its result, or an element is null
     * @throws IllegalStateException if outside a context or explicitly removed
     * @throws ClassCastException if stored or created types conflict
     */
    public Set<E> getOrCreate(Supplier<? extends Set<E>> factory) {
        Objects.requireNonNull(factory, "Set factory is mandatory");
        return slot.getOrCreate(() -> item(factory.get())).snapshot();
    }

    /**
     * Requires the current set without initializing it.
     * @return the exact immutable snapshot
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if the stored declaration conflicts
     */
    public Set<E> snapshot() {
        return slot.require().snapshot();
    }

    /**
     * Tests membership in an initialized set using normal equality semantics.
     * @param value the element to find
     * @return true if present
     * @throws NullPointerException if value is null
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if supplied or stored types conflict
     */
    public boolean contains(E value) {
        return snapshot().contains(checkedElement(value));
    }

    /**
     * Atomically adds an element to an initialized set.
     * @param value the element to add
     * @return true if membership changed
     * @throws NullPointerException if value is null
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if supplied or stored types conflict
     */
    public boolean add(E value) {
        E checked = checkedElement(value);
        return mutate(current -> current.add(checked));
    }

    /**
     * Atomically removes an element, not the whole context item.
     * @param value the element to remove
     * @return true if membership changed
     * @throws NullPointerException if value is null
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if supplied or stored types conflict
     */
    public boolean remove(E value) {
        E checked = checkedElement(value);
        return mutate(current -> current.remove(checked));
    }

    /**
     * Atomically adds a validated batch; invalid elements cannot partially apply.
     * @param values the elements to add, with duplicates collapsed
     * @return true if membership changed
     * @throws NullPointerException if collection or elements are null
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if supplied or stored types conflict
     */
    public boolean addAll(Collection<? extends E> values) {
        Set<E> additions = item(values).snapshot();
        return mutate(current -> current.addAll(additions));
    }

    /**
     * Atomically removes a validated batch, retaining the context item.
     * @param values the elements to remove
     * @return true if membership changed
     * @throws NullPointerException if collection or elements are null
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if supplied or stored types conflict
     */
    public boolean removeAll(Collection<? extends E> values) {
        Set<E> removals = item(values).snapshot();
        return mutate(current -> current.removeAll(removals));
    }

    /**
     * Atomically retains only the supplied elements in an initialized set.
     * @param values the elements eligible to remain
     * @return true if membership changed
     * @throws NullPointerException if collection or elements are null
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if supplied or stored types conflict
     */
    public boolean retainAll(Collection<? extends E> values) {
        Set<E> retained = item(values).snapshot();
        return mutate(current -> current.retainAll(retained));
    }

    /**
     * Clears an initialized set but retains a present, typed empty item.
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
     * Explicitly replaces complete contents, initializing or reintroducing the set.
     * @param values the complete contents, copied before publication
     * @throws NullPointerException if collection or elements are null
     * @throws IllegalStateException if outside a context
     * @throws ClassCastException if supplied or existing types conflict
     */
    public void replace(Collection<? extends E> values) {
        slot.put(item(values));
    }

    /**
     * Removes the entire context item and retains a transportable removal marker.
     * Unlike {@link #remove(Object)}, this does not remove just one element.
     * @return the previous immutable snapshot, or null if absent/already removed
     * @throws IllegalStateException if outside a context
     * @throws ClassCastException if the existing declaration conflicts
     */
    public Set<E> remove() {
        ContextSetItem<E> previous = slot.remove();
        return previous == null ? null : previous.snapshot();
    }

    /**
     * Atomically transforms an initialized read-only snapshot into complete
     * replacement contents. Results are copied. The callback must be short,
     * synchronous, and must not modify context storage. Exceptions preserve the
     * old reference but cannot undo mutations to application-owned elements.
     * @param updater the whole-set transformation, returning a non-null set
     * @return the exact stored immutable replacement
     * @throws NullPointerException if updater, result, or an element is null
     * @throws IllegalStateException if outside a context, absent, or removed
     * @throws ClassCastException if stored or returned types conflict
     */
    public Set<E> update(UnaryOperator<Set<E>> updater) {
        Objects.requireNonNull(updater, "Set updater is mandatory");
        return slot.update(current -> item(updater.apply(current.snapshot()))).snapshot();
    }

    private <R> R mutate(Function<Set<E>, R> operation) {
        AtomicReference<R> result = new AtomicReference<>();
        slot.update(current -> {
            Set<E> copy = new LinkedHashSet<>(current.snapshot());
            result.set(operation.apply(copy));
            return item(copy);
        });
        return result.get();
    }

    private E checkedElement(Object value) {
        return Objects.requireNonNull(elementType.cast(value), "Set element is mandatory");
    }
}
