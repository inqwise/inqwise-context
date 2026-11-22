package com.inqwise.context;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import com.inqwise.context.data.FlowId;

/** Typed access to the current flow identity, without keys or application casts. */
public final class FlowIdContext {
    private static final ContextSlot<FlowId> SLOT = ContextSlots.define("flow_id", FlowId.class,
        () -> FlowId.builder().withId(UUID.randomUUID().toString())
            .withTimestamp(Instant.ofEpochMilli(System.currentTimeMillis())).build());

    private FlowIdContext() {
    }

    /**
     * Returns the declaration for codecs, configuration, and factory providers.
     *
     * @return the shared immutable typed handle, not a captured flow
     */
    public static ContextSlot<FlowId> slot() {
        return SLOT;
    }

    /**
     * Reads the current identity without creating one.
     * @return the exact instance, or null if absent or removed
     * @throws IllegalStateException if no Vert.x context is active
     */
    public static FlowId get() {
        return SLOT.get();
    }

    /**
     * Requires an identity without creating one.
     * @return the exact instance
     * @throws IllegalStateException if absent, removed, or outside a context
     */
    public static FlowId require() {
        return SLOT.require();
    }

    /**
     * Sets an identity, including intentional reintroduction after removal.
     * @param value the non-null identity
     * @throws NullPointerException if value is null
     * @throws IllegalStateException if no Vert.x context is active
     */
    public static void put(FlowId value) {
        SLOT.put(value);
    }

    /**
     * Atomically replaces an existing identity using a synchronous transformation.
     * The updater must not modify context storage. See {@link ContextSlot#update}
     * for ownership and atomicity requirements.
     * @param updater the transformation of the existing identity
     * @return the exact stored replacement
     * @throws NullPointerException if updater or its result is null
     * @throws IllegalStateException if absent, removed, or outside a context
     */
    public static FlowId update(UnaryOperator<FlowId> updater) {
        return SLOT.update(updater);
    }

    /**
     * Removes the identity and retains its transportable removal marker.
     * @return the previous instance, or null if absent or already removed
     * @throws IllegalStateException if no Vert.x context is active
     */
    public static FlowId remove() {
        return SLOT.remove();
    }

    /**
     * Explicitly initializes an absent identity. An SPI factory overrides the
     * default random UUID and millisecond timestamp. Reads, transport import,
     * and replies never call this operation automatically.
     * @return the existing or atomically created identity
     * @throws IllegalStateException if removed, outside a context, or SPI resolution fails
     * @throws NullPointerException if a configured factory returns null
     */
    public static FlowId getOrCreate() {
        return SLOT.getOrCreate();
    }

    /**
     * Explicitly initializes using a supplied factory instead of SPI/defaults.
     * @param factory the construction function
     * @return the existing or atomically created identity
     * @throws NullPointerException if factory or its result is null
     * @throws IllegalStateException if removed or outside a context
     */
    public static FlowId getOrCreate(Supplier<? extends FlowId> factory) {
        return SLOT.getOrCreate(factory);
    }
}
