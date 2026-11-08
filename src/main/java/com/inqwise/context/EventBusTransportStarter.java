package com.inqwise.context;

import java.util.concurrent.Callable;

import io.vertx.core.Deployable;
import io.vertx.core.Promise;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.internal.logging.Logger;
import io.vertx.core.internal.logging.LoggerFactory;
import io.vertx.core.spi.VerticleFactory;

/**
 * Installs context propagation interceptors when Vert.x loads its factories.
 * This factory provides initialization only and does not create verticles.
 */
public class EventBusTransportStarter implements VerticleFactory {

    /**
     * Creates the factory discovered by Vert.x through its service descriptor.
     */
    public EventBusTransportStarter() {
    }
	
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

    /**
     * Fails creation because this factory only installs interceptors.
     *
     * @param verticleName the requested verticle name
     * @param classLoader the deployment class loader
     * @param promise the promise failed with an unsupported-operation exception
     * @deprecated use {@link #createVerticle2(String, ClassLoader, Promise)}
     */
	@Deprecated
	@Override
	public void createVerticle(String verticleName, ClassLoader classLoader, Promise<Callable<Verticle>> promise) {
        promise.fail(new UnsupportedOperationException("Context transport does not create verticles"));
	}

    /**
     * Fails deployment because this factory only installs interceptors.
     *
     * @param verticleName the requested verticle name
     * @param classLoader the deployment class loader
     * @param promise the promise failed with an unsupported-operation exception
     */
    @Override
    public void createVerticle2(String verticleName, ClassLoader classLoader,
            Promise<Callable<? extends Deployable>> promise) {
        promise.fail(new UnsupportedOperationException("Context transport does not create verticles"));
    }
	
	

}
