package com.inqwise.context;

import com.inqwise.context.spi.ContextChainDataItem;

import io.vertx.codegen.annotations.VertxGen;

/**
 * Access to metadata stored in the current Vert.x context.
 */
@VertxGen
public interface Storage {
    /**
     * Stores or replaces a value in the current context. Outside a Vert.x
     * context, the value is discarded.
     *
     * @param key the metadata key
     * @param value the value to store
     * @throws NullPointerException if the key or value is null
     */
	static void put(String key, ContextChainDataItem value) {
	    DefaultStorage.put(key, value);
	  }

    /**
     * Looks up metadata in the current context.
     *
     * @param key the metadata key
     * @return the stored value, or null if absent or outside a Vert.x context
     * @throws NullPointerException if the key is null
     */
	  static ContextChainDataItem get(String key) {
	    return DefaultStorage.get(key);
	  }
}
