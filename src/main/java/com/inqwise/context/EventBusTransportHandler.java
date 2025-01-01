package com.inqwise.context;

import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryContext;
import io.vertx.core.internal.logging.Logger;
import io.vertx.core.internal.logging.LoggerFactory;

class EventBusTransportHandler {
	private static final Logger logger = LoggerFactory.getLogger(EventBusTransportHandler.class);
	
	public void handleInbound(DeliveryContext<Object> event) {
		try {
			if(null != Vertx.currentContext()) {
				var headers = event.message().headers();
				for(var provider : ContextChainStorageHolder.getDataProviders()){
					var headerValue = headers.get(provider.key());
					if(null != headerValue) {
						DefaultStorage.put(provider.key(), provider.unmarshalFormString(headerValue));
					}
				}
			}
		} catch (Throwable e) {
			logger.error("unexpected error in handleInbound", e);
		}
		event.next();
	}
	
	public void handleOutbound(DeliveryContext<Object> event) {
		try {
			if(null != Vertx.currentContext()) {
				for(var provider : ContextChainStorageHolder.getDataProviders()){
					var storageValue = DefaultStorage.get(provider.key());
					if(null != storageValue) {
						event.message().headers().add(provider.key(), provider.marshalToString(storageValue));
					}
				}
			}
		} catch (Throwable e) {
			logger.error("unexpected error in handleOutbound", e);
		}
		event.next();
	}
}
