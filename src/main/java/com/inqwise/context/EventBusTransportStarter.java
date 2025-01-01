package com.inqwise.context;

import java.util.concurrent.Callable;

import io.vertx.core.Promise;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.internal.logging.Logger;
import io.vertx.core.internal.logging.LoggerFactory;
import io.vertx.core.spi.VerticleFactory;

public class EventBusTransportStarter implements io.vertx.core.spi.VerticleFactory {
	
	private static final Logger logger = LoggerFactory.getLogger(EventBusTransportStarter.class);
	
	@Override
	public void init(Vertx vertx) {
		VerticleFactory.super.init(vertx);
		logger.info("EventbusTransport - start");
		vertx.eventBus().addInboundInterceptor(ContextChainStorageHolder.eventBusInterceptors::handleInbound);
		vertx.eventBus().addOutboundInterceptor(ContextChainStorageHolder.eventBusInterceptors::handleOutbound);
	}
	
	@Override
	public String prefix() {
		return "~contextchain";
	}

	@Override
	public void createVerticle(String verticleName, ClassLoader classLoader, Promise<Callable<Verticle>> promise) {
		//throw Throws.notImplemented("createVerticle");
	}
	
	

}
