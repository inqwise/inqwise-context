package com.inqwise.context.proxy;

import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;

/** A generated EventBus service used by deployed test verticles. */
@ProxyGen
@VertxGen
public interface ClusterFlowService {
    /**
     * Runs the requested call/return scenario.
     *
     * @param command the test scenario
     * @return the observed result
     */
    Future<String> process(String command);
}
