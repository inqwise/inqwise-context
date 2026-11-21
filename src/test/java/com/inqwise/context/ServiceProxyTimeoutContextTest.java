package com.inqwise.context;

import java.util.UUID;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.proxy.ClusterFlowService;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.serviceproxy.ServiceBinder;
import io.vertx.serviceproxy.ServiceProxyBuilder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class ServiceProxyTimeoutContextTest {
    private record Pending(ContextInternal scope, Promise<String> response) {
    }

    @ParameterizedTest
    @MethodSource("callerModes")
    void serviceProxy_noHandlers_preservesNativeFailureAndCallerState(
            ThreadingModel model, String state, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        String address = "missing-service-" + UUID.randomUUID();
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                ContextInternal caller = ContextInternal.current();
                assertModel(model);
                FlowId original = initialize(state);
                return proxy(vertx, address).process("missing").transform(result -> {
                    assertTrue(result.failed());
                    assertEquals(ReplyFailure.NO_HANDLERS, assertInstanceOf(ReplyException.class, result.cause()).failureType());
                    assertSame(caller, ContextInternal.current());
                    assertModel(model);
                    assertState(state, original);
                    return vertx.timer(1).map(ignored -> {
                        assertSame(caller, ContextInternal.current());
                        assertModel(model);
                        assertState(state, original);
                        return ignored;
                    });
                });
            }
        }, new DeploymentOptions().setThreadingModel(model)).onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @MethodSource("lateReplyModes")
    void serviceProxy_lateReplyAfterTimeout_cannotImportMetadataOrLeakIntoNextCall(
            ThreadingModel model, String state, boolean remoteRemoval, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        String address = "slow-service-" + UUID.randomUUID();
        Promise<Pending> entered = Promise.promise();
        Promise<String> lateDispatched = Promise.promise();
        vertx.eventBus().addOutboundInterceptor(delivery -> {
            boolean late = "late-result".equals(delivery.message().body());
            String header = late ? delivery.message().headers().get("flow_id") : null;
            delivery.next();
            if (late) {
                lateDispatched.complete(header);
            }
        });
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                return new ServiceBinder(vertx).setAddress(address).register(ClusterFlowService.class, command -> {
                    if (state.equals("value")) {
                        assertEquals("caller", ((FlowId) Storage.get("flow_id")).getId());
                    } else {
                        assertState(state, null);
                    }
                    if (command.equals("followup")) {
                        return Future.succeededFuture("followup-result");
                    }
                    ContextInternal scope = ContextInternal.current();
                    Promise<String> response = scope.promise();
                    entered.complete(new Pending(scope, response));
                    return response.future();
                }).completion();
            }
        }).compose(receiver -> vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                ContextInternal caller = ContextInternal.current();
                assertModel(model);
                FlowId original = initialize(state);
                ClusterFlowService service = proxy(vertx, address);
                Future<String> pending = service.process("slow");
                return ContextPropagation.bind(entered.future()).compose(request -> pending.transform(result -> {
                    assertTrue(result.failed());
                    assertEquals(ReplyFailure.TIMEOUT, assertInstanceOf(ReplyException.class, result.cause()).failureType());
                    assertSame(caller, ContextInternal.current());
                    assertModel(model);
                    assertState(state, original);
                    request.scope().runOnContext(ignored -> {
                        assertSame(request.scope(), ContextInternal.current());
                        if (remoteRemoval) {
                            Storage.remove("flow_id");
                        } else {
                            Storage.put("flow_id", FlowId.builder().withId("too-late-server").build());
                        }
                        request.response().complete("late-result");
                    });
                    return ContextPropagation.bind(lateDispatched.future()).compose(header -> {
                        FlowIdService codec = new FlowIdService();
                        if (remoteRemoval) {
                            assertTrue(codec.isRemoval(header));
                        } else {
                            assertEquals("too-late-server", ((FlowId) codec.unmarshalFormString(header)).getId());
                        }
                        assertSame(caller, ContextInternal.current());
                        assertModel(model);
                        assertState(state, original);
                        return service.process("followup");
                    }).compose(reply -> {
                        assertEquals("followup-result", reply);
                        assertSame(caller, ContextInternal.current());
                        assertState(state, original);
                        return vertx.timer(1).map(tick -> {
                            assertSame(caller, ContextInternal.current());
                            assertModel(model);
                            assertState(state, original);
                            return tick;
                        });
                    });
                }));
            }
        }, new DeploymentOptions().setThreadingModel(model))).onComplete(context.succeedingThenComplete());
    }

    private static ClusterFlowService proxy(Vertx vertx, String address) {
        return new ServiceProxyBuilder(vertx).setAddress(address)
            .setOptions(new DeliveryOptions().setSendTimeout(500)).build(ClusterFlowService.class);
    }

    private static FlowId initialize(String state) {
        FlowId original = FlowId.builder().withId("caller").build();
        if (!state.equals("empty")) {
            Storage.put("flow_id", original);
        }
        if (state.equals("removed")) {
            Storage.remove("flow_id");
        }
        return original;
    }

    private static void assertState(String state, FlowId original) {
        if (state.equals("value")) {
            assertSame(original, Storage.get("flow_id"));
        } else {
            assertNull(Storage.get("flow_id"));
        }
        assertEquals(state.equals("removed"), DefaultStorage.isRemoved(DefaultStorage.getEntry(ContextInternal.current(), "flow_id")));
    }

    private static void assertModel(ThreadingModel model) {
        assertEquals(model, ContextInternal.current().threadingModel());
        assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
    }

    private static Stream<Arguments> callerModes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of("empty", "value", "removed").map(state -> Arguments.of(model, state)));
    }

    private static Stream<Arguments> lateReplyModes() {
        return callerModes().flatMap(mode -> Stream.of(false, true)
            .map(removal -> Arguments.of(mode.get()[0], mode.get()[1], removal)));
    }
}
