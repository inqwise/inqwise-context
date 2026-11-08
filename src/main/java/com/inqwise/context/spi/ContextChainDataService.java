package com.inqwise.context.spi;

/**
 * Serializes one metadata type for transport in EventBus headers.
 * Implementations are discovered through {@link java.util.ServiceLoader} and
 * shared across contexts, so they must support concurrent use. Each provider
 * should use a distinct, stable header key.
 */
public interface ContextChainDataService {
    /**
     * Returns the key used both in context storage and in transport headers.
     *
     * @return the metadata key
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
}
