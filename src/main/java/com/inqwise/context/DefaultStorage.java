package com.inqwise.context;

import static com.inqwise.context.ContextChainStorageHolder.STORAGE_CONTEXT;
import static io.vertx.core.spi.context.storage.AccessMode.CONCURRENT;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.inqwise.context.spi.ContextChainDataItem;

import io.vertx.core.internal.ContextInternal;
import io.vertx.core.internal.logging.Logger;
import io.vertx.core.internal.logging.LoggerFactory;
/**
 * Helper to store data in the local context.
 */
class DefaultStorage {
	private static final Logger log = LoggerFactory.getLogger(DefaultStorage.class);
	
	static void put(String key, ContextChainDataItem value) {
		Objects.requireNonNull(key);
		Objects.requireNonNull(value);
		ContextInternal ctx = ContextInternal.current();
		if (ctx == null) {
			if (log.isTraceEnabled()) {
				log.trace("Attempt to set contextual data from a non Vert.x thread", new Exception());
			}
	    } else {
	    	contextualDataMap(ctx).put(key, value);
	    }
	}
	
	static ContextChainDataItem get(String key) {
		Objects.requireNonNull(key);
	    ContextInternal ctx = ContextInternal.current();
	    if (ctx != null) {
	    	return contextualDataMap(ctx).get(key);
	    }
	    return null;
	}
	
	@SuppressWarnings({"unchecked", "rawtypes"})
	private final static ConcurrentMap<String, ContextChainDataItem> contextualDataMap(ContextInternal ctx) {
	    ConcurrentMap lcd = Objects.requireNonNull(ctx).getLocal(STORAGE_CONTEXT, CONCURRENT, ConcurrentHashMap::new);
	    return (ConcurrentMap<String, ContextChainDataItem>) lcd;
	}
}
