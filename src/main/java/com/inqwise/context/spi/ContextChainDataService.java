package com.inqwise.context.spi;

/**
 * Serializes one metadata type for transport in EventBus headers.
 * Implementations are discovered through {@link java.util.ServiceLoader} and
 * shared across contexts, so they must support concurrent use. Each provider
 * must use a non-blank, stable header key. Header keys are compared without
 * regard to case during discovery; the first valid registration wins.
 */
public interface ContextChainDataService {
    /**
     * Returns the key used both in context storage and in transport headers.
     *
     * @return the non-null, non-blank metadata key
     */
	String key();
    /**
     * Serializes a value supported by this provider.
     *
     * @param model the metadata value
     * @return its transport representation
     */
	String marshalToString(ContextChainDataItem model);
    /**
     * Deserializes a transport representation. The historical method spelling
     * is retained for compatibility with existing providers.
     *
     * @param str the serialized metadata
     * @return the decoded metadata value
     */
	ContextChainDataItem unmarshalFormString(String str);

    /**
     * Encodes removal for this provider. Providers without wire-level removal
     * support omit the outgoing header instead.
     *
     * @return the removal representation, or null if unsupported
     */
    default String marshalRemovalToString() {
        return null;
    }

    /**
     * Recognizes an explicit removal before decoding a metadata value.
     * Removal uses the same inbound replacement policy as a value.
     *
     * @param str the serialized metadata
     * @return true if the representation denotes removal
     */
    default boolean isRemoval(String str) {
        return false;
    }

    /**
     * Determines whether inbound metadata may replace an existing value in the
     * receiving context. Returning false preserves local values, including the
     * caller's value when processing a reply. This policy applies to values and
     * removal markers. Empty contexts still accept data.
     *
     * @return true to replace existing values, the historical default
     */
    default boolean replaceExistingOnInbound() {
        return true;
    }
}
