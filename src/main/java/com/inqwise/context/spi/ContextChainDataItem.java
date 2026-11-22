package com.inqwise.context.spi;

/**
 * Marker for metadata values held in context storage and serialized by a
 * {@link ContextChainDataService} for propagation.
 * Values should be immutable or independently thread-safe. Storage retains
 * references without copying and does not protect mutable fields. For atomic
 * local changes, prefer immutable replacements through
 * {@link com.inqwise.context.ContextSlot#update(java.util.function.UnaryOperator)}.
 * Codecs must be able to encode a consistent value while other flow callbacks run.
 */
public interface ContextChainDataItem {

}
