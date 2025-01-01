package com.inqwise.context;

import com.inqwise.context.spi.ContextChainDataItem;

import io.vertx.codegen.annotations.VertxGen;

@VertxGen
public interface Storage {
	static void put(String key, ContextChainDataItem value) {
	    DefaultStorage.put(key, value);
	  }

	  static ContextChainDataItem get(String key) {
	    return DefaultStorage.get(key);
	  }
}
