package com.inqwise.context;

import com.inqwise.context.spi.ContextChainDataItem;

import io.vertx.codegen.annotations.VertxGen;

/**
 * Access to metadata stored in the current Vert.x context.
 */
@VertxGen
public interface Storage {
    /**
     * Stores or replaces a value in the current Vert.x context.
     *
     * @param key the metadata key
     * @param value the value to store
     * @throws NullPointerException if the key or value is null
     * @throws IllegalStateException if called outside a Vert.x context
     */
	static void put(String key, ContextChainDataItem value) {
	    DefaultStorage.put(key, value);
	  }

    /**
     * Looks up metadata in the current context.
     *
     * @param key the metadata key
     * @return the stored value, or null if absent in the current context
     * @throws NullPointerException if the key is null
     * @throws IllegalStateException if called outside a Vert.x context
     */
	  static ContextChainDataItem get(String key) {
	    return DefaultStorage.get(key);
	  }

    /**
     * Removes a value from the current context. Supporting providers, including
     * FlowId, transport an explicit removal on calls and replies; others omit
     * their outgoing header. Inbound removal follows the provider's value
     * replacement policy, so existing FlowId values remain unchanged remotely.
     * A deletion marker prevents providers that preserve existing values from
     * restoring a removed value through a reply.
     * An explicit {@link #put(String, ContextChainDataItem)} replaces the marker.
     * Providers that allow inbound replacement can overwrite the marker.
     *
     * @param key the metadata key
     * @return the removed instance, or null if no value was present
     * @throws NullPointerException if the key is null
     * @throws IllegalStateException if called outside a Vert.x context
     */
    static ContextChainDataItem remove(String key) {
        return DefaultStorage.remove(key);
    }
}
