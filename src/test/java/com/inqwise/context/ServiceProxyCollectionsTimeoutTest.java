package com.inqwise.context;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.extension.StringListContext;
import com.inqwise.context.extension.StringMapContext;
import com.inqwise.context.extension.StringSetContext;
import com.inqwise.context.proxy.ClusterFlowService;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
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

import static com.inqwise.context.extension.StringListContext.STEPS;
import static com.inqwise.context.extension.StringMapContext.VALUES;
import static com.inqwise.context.extension.StringSetContext.LABELS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class ServiceProxyCollectionsTimeoutTest {
    private static final ContextMap<String, String> PRIVATE = ContextMaps.define("private_map", String.class, String.class);

    private record Pending(ContextInternal context, Promise<String> response) {
    }

    private record Items(ContextMapItem<String, String> map, ContextSetItem<String> set, ContextListItem<String> list) {
        static Items current() {
            return new Items(VALUES.slot().get(), LABELS.slot().get(), STEPS.slot().get());
        }

        void assertCurrent() {
            assertSame(map, VALUES.slot().get());
            assertSame(set, LABELS.slot().get());
            assertSame(list, STEPS.slot().get());
        }
    }

    private record Caller(ContextInternal context, ThreadingModel model, String state, FlowId flow,
            ContextMapItem<String, String> privateItem, Items items) {
        static Caller capture(ThreadingModel model, String state, FlowId flow) {
            return new Caller(ContextInternal.current(), model, state, flow, PRIVATE.slot().require(), Items.current());
        }

        void assertState() {
            assertSame(context, ContextInternal.current());
            assertEquals(model, context.threadingModel());
            assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
            assertSame(flow, FlowIdContext.get());
            assertEquals(flow == null, DefaultStorage.isRemoved(DefaultStorage.getEntry(context, "flow_id")));
            assertSame(privateItem, PRIVATE.slot().require());
            ServiceProxyCollectionsTimeoutTest.assertState(state, "pending");
        }

        void assertCurrent() {
            assertState();
            items.assertCurrent();
        }
    }

    @ParameterizedTest
    @MethodSource("callerModes")
    void serviceProxy_noHandlers_preservesCollectionStateAndNativeFailure(
            ThreadingModel model, String state, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        install(vertx);
        String address = "missing-collections-" + UUID.randomUUID();
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                FlowId flow = FlowId.builder().withId("caller").build();
                FlowIdContext.put(flow);
                prepare(state, "pending");
                PRIVATE.replace(Map.of("caller", "private"));
                Caller caller = Caller.capture(model, state, flow);
                return proxy(vertx, address).process("missing").transform(result -> {
                    assertTrue(result.failed());
                    assertEquals(ReplyFailure.NO_HANDLERS, assertInstanceOf(ReplyException.class, result.cause()).failureType());
                    caller.assertCurrent();
                    return vertx.timer(1).map(tick -> {
                        caller.assertCurrent();
                        return tick;
                    });
                });
            }
        }, new DeploymentOptions().setThreadingModel(model)).onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @MethodSource("lateReplyModes")
    void serviceProxy_lateReplyAfterTimeout_cannotReplaceCollectionsOrContaminateNextCall(
            ThreadingModel model, String state, boolean remoteRemoval, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        install(vertx);
        String address = "slow-collections-" + UUID.randomUUID();
        Promise<Pending> entered = Promise.promise();
        Promise<MultiMap> lateDispatched = Promise.promise();
        vertx.eventBus().addOutboundInterceptor(delivery -> {
            boolean late = "late-result".equals(delivery.message().body());
            MultiMap headers = late ? MultiMap.caseInsensitiveMultiMap().addAll(delivery.message().headers()) : null;
            delivery.next();
            if (late) {
                lateDispatched.complete(headers);
            }
        });
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                return new ServiceBinder(vertx).setAddress(address).register(ClusterFlowService.class, command -> {
                    assertNull(PRIVATE.slot().get());
                    assertState(state, command.equals("followup") ? "pending" : "sent");
                    if (command.equals("followup")) {
                        assertEquals(remoteRemoval, DefaultStorage.isRemoved(
                            DefaultStorage.getEntry(ContextInternal.current(), "flow_id")));
                        if (!remoteRemoval) {
                            assertEquals("caller-current", FlowIdContext.require().getId());
                        }
                        return Future.succeededFuture("followup-result");
                    }
                    assertEquals("caller-sent", FlowIdContext.require().getId());
                    ContextInternal scope = ContextInternal.current();
                    Promise<String> response = scope.promise();
                    entered.complete(new Pending(scope, response));
                    return response.future();
                }).completion();
            }
        }).compose(receiver -> vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                FlowIdContext.put(FlowId.builder().withId("caller-sent").build());
                prepare(state, "sent");
                PRIVATE.replace(Map.of("caller", "private"));
                ClusterFlowService service = proxy(vertx, address);
                Future<String> pending = service.process("slow");
                return ContextPropagation.bind(entered.future()).compose(request -> {
                    prepare(state, "pending");
                    FlowId current = remoteRemoval ? null : FlowId.builder().withId("caller-current").build();
                    if (remoteRemoval) {
                        FlowIdContext.remove();
                    } else {
                        FlowIdContext.put(current);
                    }
                    Caller caller = Caller.capture(model, state, current);
                    return pending.transform(result -> {
                        assertTrue(result.failed());
                        assertEquals(ReplyFailure.TIMEOUT, assertInstanceOf(ReplyException.class, result.cause()).failureType());
                        caller.assertCurrent();
                        request.context().runOnContext(ignored -> {
                            assertSame(request.context(), ContextInternal.current());
                            if (remoteRemoval) {
                                remove();
                                FlowIdContext.remove();
                            } else {
                                replace("too-late-server");
                                FlowIdContext.put(FlowId.builder().withId("too-late-server").build());
                            }
                            request.response().complete("late-result");
                        });
                        return ContextPropagation.bind(lateDispatched.future()).compose(headers -> {
                            assertLateHeaders(headers, remoteRemoval);
                            caller.assertCurrent();
                            return service.process("followup");
                        }).compose(reply -> {
                            assertEquals("followup-result", reply);
                            // A normal follow-up reply can decode fresh collection wrappers, unlike an expired reply.
                            caller.assertState();
                            Items returned = Items.current();
                            return vertx.timer(1).map(tick -> {
                                caller.assertState();
                                returned.assertCurrent();
                                return tick;
                            });
                        });
                    });
                });
            }
        }, new DeploymentOptions().setThreadingModel(model))).onComplete(context.succeedingThenComplete());
    }

    private static void install(Vertx vertx) {
        EventBusTransportHandler transport = new EventBusTransportHandler(() -> List.of(
            new StringMapContext.Codec(), new StringSetContext.Codec(), new StringListContext.Codec()));
        vertx.eventBus().addOutboundInterceptor(transport::handleOutbound);
        vertx.eventBus().addInboundInterceptor(transport::handleInbound);
    }

    private static ClusterFlowService proxy(Vertx vertx, String address) {
        return new ServiceProxyBuilder(vertx).setAddress(address)
            .setOptions(new DeliveryOptions().setSendTimeout(500)).build(ClusterFlowService.class);
    }

    private static void assertLateHeaders(MultiMap headers, boolean removal) {
        assertNull(headers.get(PRIVATE.slot().key()));
        var map = new StringMapContext.Codec();
        var set = new StringSetContext.Codec();
        var list = new StringListContext.Codec();
        var flow = new FlowIdService();
        if (removal) {
            assertTrue(map.isRemoval(headers.get(VALUES.slot().key())));
            assertTrue(set.isRemoval(headers.get(LABELS.slot().key())));
            assertTrue(list.isRemoval(headers.get(STEPS.slot().key())));
            assertTrue(flow.isRemoval(headers.get("flow_id")));
        } else {
            assertEquals(Map.of("value", "too-late-server"), map.unmarshalFormString(headers.get(VALUES.slot().key())).snapshot());
            assertEquals(Set.of("too-late-server"), set.unmarshalFormString(headers.get(LABELS.slot().key())).snapshot());
            assertEquals(List.of("too-late-server", "too-late-server"), list.unmarshalFormString(headers.get(STEPS.slot().key())).snapshot());
            assertEquals("too-late-server", ((FlowId) flow.unmarshalFormString(headers.get("flow_id"))).getId());
        }
    }

    private static void prepare(String state, String value) {
        switch (state) {
            case "value" -> replace(value);
            case "empty" -> {
                VALUES.replace(Map.of());
                LABELS.replace(Set.of());
                STEPS.replace(List.of());
            }
            case "removed" -> remove();
            case "absent" -> { }
            default -> throw new AssertionError("Unknown collection state");
        }
    }

    private static void replace(String value) {
        VALUES.replace(Map.of("value", value));
        LABELS.replace(Set.of(value));
        STEPS.replace(List.of(value, value));
    }

    private static void remove() {
        VALUES.remove();
        LABELS.remove();
        STEPS.remove();
    }

    private static void assertState(String state, String value) {
        switch (state) {
            case "value" -> {
                assertEquals(Map.of("value", value), VALUES.snapshot());
                assertEquals(Set.of(value), LABELS.snapshot());
                assertEquals(List.of(value, value), STEPS.snapshot());
            }
            case "empty" -> {
                assertEquals(Map.of(), VALUES.snapshot());
                assertEquals(Set.of(), LABELS.snapshot());
                assertEquals(List.of(), STEPS.snapshot());
            }
            case "removed", "absent" -> {
                for (String key : List.of(VALUES.slot().key(), LABELS.slot().key(), STEPS.slot().key())) {
                    assertNull(Storage.get(key));
                    assertEquals(state.equals("removed"), DefaultStorage.isRemoved(
                        DefaultStorage.getEntry(ContextInternal.current(), key)));
                }
            }
            default -> throw new AssertionError("Unknown collection state");
        }
    }

    private static Stream<Arguments> callerModes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of("value", "empty", "removed", "absent").map(state -> Arguments.of(model, state)));
    }

    private static Stream<Arguments> lateReplyModes() {
        return callerModes().flatMap(mode -> Stream.of(false, true)
            .map(removal -> Arguments.of(mode.get()[0], mode.get()[1], removal)));
    }
}
