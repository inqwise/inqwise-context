package com.inqwise.context;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.data.FlowIdService.CodecProfile;
import com.inqwise.context.data.FlowIdTestSupport;
import com.inqwise.context.proxy.ClusterFlowService;
import io.vertx.core.Context;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VerticleBase;
import io.vertx.core.ThreadingModel;
import io.vertx.core.eventbus.DeliveryContext;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;
import io.vertx.serviceproxy.ServiceBinder;
import io.vertx.serviceproxy.ServiceException;
import io.vertx.serviceproxy.ServiceProxyBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.EnumSource;

import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static com.inqwise.context.ClusterTestSupport.await;
import static com.inqwise.context.ClusterTestSupport.clusterConfig;
import static com.inqwise.context.ClusterTestSupport.startNode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(30)
class ClusteredFlowIdTransportTest {
    private enum ReturnAction {
        REPLY, REPLACE_AND_REPLY, REPLACE_AND_FAIL
    }

    private Vertx sender;
    private Vertx receiver;
    private Vertx downstream;
    private HazelcastClusterManager senderManager;
    private HazelcastClusterManager receiverManager;
    private HazelcastClusterManager downstreamManager;

    @BeforeAll
    @Timeout(60)
    void startCluster() throws Exception {
        String cluster = "flow-id-test-" + UUID.randomUUID();
        senderManager = new HazelcastClusterManager(clusterConfig(cluster, null));
        sender = await(startNode(senderManager));
        int seedPort = senderManager.getHazelcastInstance().getCluster().getLocalMember().getAddress().getPort();
        receiverManager = new HazelcastClusterManager(clusterConfig(cluster, "127.0.0.1:" + seedPort));
        receiver = await(startNode(receiverManager));
        downstreamManager = new HazelcastClusterManager(clusterConfig(cluster, "127.0.0.1:" + seedPort));
        downstream = await(startNode(downstreamManager));

        assertTrue(sender.isClustered());
        assertTrue(receiver.isClustered());
        assertNotEquals(senderManager.getNodeId(), receiverManager.getNodeId());
        assertEquals(3, downstreamManager.getHazelcastInstance().getCluster().getMembers().size());
        assertNotEquals(senderManager.getNodeInfo().port(), receiverManager.getNodeInfo().port());
        assertNotEquals(receiverManager.getNodeId(), downstreamManager.getNodeId());
        assertNotEquals(senderManager.getNodeId(), downstreamManager.getNodeId());
        assertNotEquals(downstreamManager.getNodeInfo().port(), senderManager.getNodeInfo().port());
        assertNotEquals(downstreamManager.getNodeInfo().port(), receiverManager.getNodeInfo().port());
        assertEquals("127.0.0.1", senderManager.getNodeInfo().host());
        assertEquals("127.0.0.1", receiverManager.getNodeInfo().host());
    }

    @AfterAll
    @Timeout(60)
    void stopCluster() throws Exception {
        List<Future<?>> closing = new ArrayList<>();
        if (sender != null) {
            closing.add(sender.close());
        }
        if (receiver != null) {
            closing.add(receiver.close());
        }
        if (downstream != null) {
            closing.add(downstream.close());
        }
        try {
            await(Future.join(closing));
        } finally {
            // Also release partially started members if cluster startup failed.
            for (HazelcastClusterManager manager : Arrays.asList(senderManager, receiverManager, downstreamManager)) {
                if (manager != null && manager.getHazelcastInstance() != null
                        && manager.getHazelcastInstance().getLifecycleService().isRunning()) {
                    manager.getHazelcastInstance().shutdown();
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    void request_remoteNodeAndCodecProfiles_preserveValuesAndCallerInstance(
            CodecProfile senderProfile, CodecProfile receiverProfile, ReturnAction action, VertxTestContext context) {
        FlowId original = FlowId.builder().withId("origin:\u0394\nquoted-\"id\"")
            .withTimestamp(Instant.parse("2026-11-08T10:00:00.123Z")).build();
        requestAcrossNodes(senderProfile, receiverProfile, action, original, context);
    }

    @ParameterizedTest
    @EnumSource(CodecProfile.class)
    void request_payloadAtByteLimit_roundTripsOverCluster(CodecProfile profile, VertxTestContext context) {
        requestAcrossNodes(profile, profile, ReturnAction.REPLY, FlowIdTestSupport.maximumPayload(), context);
    }

    private void requestAcrossNodes(CodecProfile senderProfile, CodecProfile receiverProfile, ReturnAction action,
            FlowId original, VertxTestContext context) {
        FlowIdService senderCodec = new FlowIdService(senderProfile);
        FlowIdService receiverCodec = new FlowIdService(receiverProfile);
        Handler<DeliveryContext<Object>> senderWriter = writer(senderCodec);
        Handler<DeliveryContext<Object>> receiverWriter = writer(receiverCodec);
        sender.eventBus().addOutboundInterceptor(senderWriter);
        receiver.eventBus().addOutboundInterceptor(receiverWriter);
        String address = "cluster-flow-" + UUID.randomUUID();
        FlowId replacement = FlowId.builder().withId("remote-replacement").build();
        AtomicReference<Throwable> remoteFailure = new AtomicReference<>();
        var consumer = receiver.eventBus().<String>consumer(address, message -> {
            try {
                assertSame(receiver, ContextInternal.current().owner());
                FlowId received = (FlowId) Storage.get(senderCodec.key());
                assertNotSame(original, received);
                assertEquals(original.getId(), received.getId());
                assertEquals(original.getTimestamp(), received.getTimestamp());
                assertEquals(senderCodec.marshalToString(original), message.headers().get(senderCodec.key()));
                if (action != ReturnAction.REPLY) {
                    Storage.put(senderCodec.key(), replacement);
                }
                if (action == ReturnAction.REPLACE_AND_FAIL) {
                    message.fail(37, "controlled remote failure");
                } else {
                    message.reply("remote-done");
                }
            } catch (Throwable error) {
                remoteFailure.set(error);
                message.fail(500, "Remote test assertion failed");
            }
        });

        consumer.completion().compose(ignored -> inFlow(sender, "caller", item -> {
            Storage.put(senderCodec.key(), original);
            return sender.eventBus().<String>request(address, "request", new DeliveryOptions().setSendTimeout(5_000))
                .transform(result -> {
                    if (remoteFailure.get() != null) {
                        throw new AssertionError("Remote node assertion failed", remoteFailure.get());
                    }
                    assertSame(sender, ContextInternal.current().owner());
                    assertSame(original, Storage.get(senderCodec.key()));
                    assertEquals(action == ReturnAction.REPLACE_AND_FAIL, result.failed());
                    if (result.failed()) {
                        assertEquals(37, assertInstanceOf(ReplyException.class, result.cause()).failureCode());
                    } else {
                        assertEquals("remote-done", result.result().body());
                        String header = result.result().headers().get(senderCodec.key());
                        FlowId expected = action == ReturnAction.REPLY ? original : replacement;
                        assertEquals(receiverCodec.marshalToString(expected), header);
                        FlowId returned = (FlowId) senderCodec.unmarshalFormString(header);
                        assertEquals(expected.getId(), returned.getId());
                        assertEquals(expected.getTimestamp(), returned.getTimestamp());
                    }
                    return Future.succeededFuture();
                });
        })).eventually(consumer::unregister).andThen(ignored -> {
            sender.eventBus().removeOutboundInterceptor(senderWriter);
            receiver.eventBus().removeOutboundInterceptor(receiverWriter);
        }).onComplete(context.succeedingThenComplete());
    }

    @Test
    void request_overlappingRemoteFlows_doNotLeakInstancesOrValues(VertxTestContext context) {
        String address = "cluster-overlap-" + UUID.randomUUID();
        Promise<Void> bothEntered = Promise.promise();
        AtomicInteger entered = new AtomicInteger();
        AtomicReference<Throwable> remoteFailure = new AtomicReference<>();
        var consumer = receiver.eventBus().<String>consumer(address, message -> {
            Context flowContext = Vertx.currentContext();
            try {
                FlowId received = (FlowId) Storage.get("flow_id");
                assertEquals(message.body(), received.getId());
                FlowId replacement = FlowId.builder().withId(message.body() + "-remote").build();
                Storage.put("flow_id", replacement);
                bothEntered.future().onSuccess(ignored -> flowContext.runOnContext(resumed -> {
                    try {
                        assertSame(replacement, Storage.get("flow_id"));
                        message.reply(replacement.getId());
                    } catch (Throwable error) {
                        remoteFailure.set(error);
                        message.fail(500, "Remote test assertion failed");
                    }
                }));
                if (entered.incrementAndGet() == 2) {
                    bothEntered.complete();
                }
            } catch (Throwable error) {
                remoteFailure.set(error);
                message.fail(500, "Remote test assertion failed");
            }
        });
        consumer.completion().compose(ignored -> {
            List<Future<Void>> flows = new ArrayList<>();
            for (String id : List.of("first", "second")) {
                flows.add(inFlow(sender, id, item -> {
                    FlowId original = FlowId.builder().withId(id).build();
                    Storage.put("flow_id", original);
                    return sender.eventBus().<String>request(address, id, new DeliveryOptions().setSendTimeout(5_000))
                        .map(reply -> {
                            assertSame(original, Storage.get("flow_id"));
                            assertEquals(id + "-remote", reply.body());
                            return reply;
                        });
                }));
            }
            return Future.join(flows).map(done -> {
                assertNull(remoteFailure.get());
                return done;
            });
        }).eventually(consumer::unregister).onComplete(context.succeedingThenComplete());
    }

    @Test
    void request_missingMetadata_doesNotInventFlowIdOnRemoteNode(VertxTestContext context) {
        String address = "cluster-empty-" + UUID.randomUUID();
        var consumer = receiver.eventBus().<String>consumer(address, message -> {
            if (Storage.get("flow_id") != null || message.headers().contains("flow_id")) {
                message.fail(500, "Unexpected metadata in an independent remote flow");
            } else {
                message.reply("empty");
            }
        });
        consumer.completion().compose(ignored -> sender.eventBus().<String>request(address, "request"))
            .map(reply -> {
                assertEquals("empty", reply.body());
                assertNull(reply.headers().get("flow_id"));
                return reply;
            }).eventually(consumer::unregister).onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @MethodSource("chainScenarios")
    void serviceProxy_threeDeployedNodes_keepLocalChangesOnCallAndReturn(
            ThreadingModel model, boolean failAtC, boolean removeAtC, VertxTestContext context) {
        String bAddress = "service-b-" + UUID.randomUUID();
        String cAddress = "service-c-" + UUID.randomUUID();
        List<String> trace = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> remoteFailure = new AtomicReference<>();
        FlowId original = FlowId.builder().withId("flow-A")
            .withTimestamp(Instant.parse("2026-11-08T10:00:00.123Z")).build();
        AtomicReference<FlowId> bReplacement = new AtomicReference<>();
        Handler<DeliveryContext<Object>> aWriter = writer(new FlowIdService(CodecProfile.LEGACY));
        Handler<DeliveryContext<Object>> bWriter = writer(new FlowIdService(CodecProfile.JSON));
        Handler<DeliveryContext<Object>> cWriter = writer(new FlowIdService(CodecProfile.GZIP));
        sender.eventBus().addOutboundInterceptor(aWriter);
        receiver.eventBus().addOutboundInterceptor(bWriter);
        downstream.eventBus().addOutboundInterceptor(cWriter);

        VerticleBase cVerticle = new VerticleBase() {
            private MessageConsumer<JsonObject> binding;

            @Override
            public Future<?> start() {
                binding = new ServiceBinder(vertx).setAddress(cAddress).register(ClusterFlowService.class, command -> {
                    try {
                        assertSame(downstream, ContextInternal.current().owner());
                        FlowId incoming = (FlowId) Storage.get("flow_id");
                        assertEquals("flow-B", incoming.getId());
                        assertEquals(original.getTimestamp(), incoming.getTimestamp());
                        assertNotSame(bReplacement.get(), incoming);
                        trace.add("C:receive:" + incoming.getId());
                        if (removeAtC) {
                            assertSame(incoming, Storage.remove("flow_id"));
                            assertNull(Storage.get("flow_id"));
                            trace.add("C:remove:flow-B");
                        } else {
                            FlowId replacement = FlowId.builderFrom(incoming).withId("flow-C").build();
                            Storage.put("flow_id", replacement);
                            assertSame(replacement, Storage.get("flow_id"));
                            trace.add("C:replace:flow-C");
                        }
                        return failAtC ? Future.failedFuture(new ServiceException(73, "Controlled service C failure"))
                            : Future.succeededFuture("completed-by-C");
                    } catch (Throwable error) {
                        remoteFailure.compareAndSet(null, error);
                        return Future.failedFuture(error);
                    }
                });
                return binding.completion();
            }

            @Override
            public Future<?> stop() {
                return binding.unregister();
            }
        };

        VerticleBase bVerticle = new VerticleBase() {
            private MessageConsumer<JsonObject> binding;

            @Override
            public Future<?> start() {
                ClusterFlowService next = proxy(vertx, cAddress);
                binding = new ServiceBinder(vertx).setAddress(bAddress).register(ClusterFlowService.class, command -> {
                    try {
                        assertSame(receiver, ContextInternal.current().owner());
                        FlowId incoming = (FlowId) Storage.get("flow_id");
                        assertEquals("flow-A", incoming.getId());
                        assertEquals(original.getTimestamp(), incoming.getTimestamp());
                        assertNotSame(original, incoming);
                        trace.add("B:receive:" + incoming.getId());
                        FlowId replacement = FlowId.builderFrom(incoming).withId("flow-B").build();
                        bReplacement.set(replacement);
                        Storage.put("flow_id", replacement);
                        trace.add("B:replace:flow-B");
                        return next.process(command).transform(result -> {
                            try {
                                assertSame(receiver, ContextInternal.current().owner());
                                assertSame(replacement, Storage.get("flow_id"));
                                assertEquals(failAtC, result.failed());
                                if (failAtC) {
                                    assertEquals(73, assertInstanceOf(ReplyException.class, result.cause()).failureCode());
                                } else {
                                    assertEquals("completed-by-C", result.result());
                                }
                                trace.add("B:return:flow-B");
                                return result.succeeded() ? Future.succeededFuture(result.result())
                                    : Future.<String>failedFuture(result.cause());
                            } catch (Throwable error) {
                                remoteFailure.compareAndSet(null, error);
                                return Future.failedFuture(error);
                            }
                        });
                    } catch (Throwable error) {
                        remoteFailure.compareAndSet(null, error);
                        return Future.failedFuture(error);
                    }
                });
                return binding.completion();
            }

            @Override
            public Future<?> stop() {
                return binding.unregister();
            }
        };

        VerticleBase aVerticle = new VerticleBase() {
            @Override
            public Future<?> start() {
                assertNull(Storage.get("flow_id"));
                Storage.put("flow_id", original);
                trace.add("A:add:flow-A");
                return proxy(vertx, bAddress).process("process").transform(result -> {
                    if (remoteFailure.get() != null) {
                        throw new AssertionError("Remote service assertion failed", remoteFailure.get());
                    }
                    assertSame(sender, ContextInternal.current().owner());
                    assertSame(original, Storage.get("flow_id"));
                    assertEquals(failAtC, result.failed());
                    if (failAtC) {
                        assertEquals(73, assertInstanceOf(ReplyException.class, result.cause()).failureCode());
                    } else {
                        assertEquals("completed-by-C", result.result());
                    }
                    trace.add("A:return:flow-A");
                    return Future.succeededFuture();
                });
            }
        };

        AtomicReference<String> aDeployment = new AtomicReference<>();
        AtomicReference<String> bDeployment = new AtomicReference<>();
        AtomicReference<String> cDeployment = new AtomicReference<>();
        downstream.deployVerticle(cVerticle, new DeploymentOptions().setThreadingModel(model)).compose(id -> {
            cDeployment.set(id);
            return receiver.deployVerticle(bVerticle, new DeploymentOptions().setThreadingModel(model));
        }).compose(id -> {
            bDeployment.set(id);
            return sender.deployVerticle(aVerticle, new DeploymentOptions().setThreadingModel(model));
        }).map(id -> {
            aDeployment.set(id);
            assertEquals(List.of("A:add:flow-A", "B:receive:flow-A", "B:replace:flow-B",
                "C:receive:flow-B", removeAtC ? "C:remove:flow-B" : "C:replace:flow-C",
                "B:return:flow-B", "A:return:flow-A"), trace);
            return id;
        }).eventually(() -> {
            List<Future<?>> stopping = new ArrayList<>();
            if (aDeployment.get() != null) {
                stopping.add(sender.undeploy(aDeployment.get()));
            }
            if (bDeployment.get() != null) {
                stopping.add(receiver.undeploy(bDeployment.get()));
            }
            if (cDeployment.get() != null) {
                stopping.add(downstream.undeploy(cDeployment.get()));
            }
            return Future.join(stopping).mapEmpty();
        }).andThen(ignored -> {
            sender.eventBus().removeOutboundInterceptor(aWriter);
            receiver.eventBus().removeOutboundInterceptor(bWriter);
            downstream.eventBus().removeOutboundInterceptor(cWriter);
        }).onComplete(context.succeedingThenComplete());
    }

    private static ClusterFlowService proxy(Vertx vertx, String address) {
        return new ServiceProxyBuilder(vertx).setAddress(address)
            .setOptions(new DeliveryOptions().setSendTimeout(5_000)).build(ClusterFlowService.class);
    }

    private static Stream<Arguments> chainScenarios() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER)
            .flatMap(model -> Stream.of(false, true).flatMap(fail -> Stream.of(false, true)
                .map(remove -> Arguments.of(model, fail, remove))));
    }

    @ParameterizedTest
    @EnumSource(CodecProfile.class)
    void request_removedFlow_clearsStaleHeadersAndStaysAbsentUntilExplicitPut(
            CodecProfile profile, VertxTestContext context) {
        String address = "cluster-remove-" + UUID.randomUUID();
        FlowIdService codec = new FlowIdService(profile);
        Handler<DeliveryContext<Object>> selectedWriter = writer(codec);
        sender.eventBus().addOutboundInterceptor(selectedWriter);
        FlowId original = FlowId.builder().withId("original").build();
        FlowId readded = FlowId.builder().withId("re-added").build();
        FlowId peer = FlowId.builder().withId("peer").build();
        AtomicReference<Throwable> remoteFailure = new AtomicReference<>();
        var consumer = receiver.eventBus().<String>consumer(address, message -> {
            try {
                if (message.body().equals("removed")) {
                    assertTrue(codec.isRemoval(message.headers().get("flow_id")));
                    assertNull(Storage.get("flow_id"));
                    Storage.put("flow_id", peer);
                } else {
                    assertEquals("re-added", ((FlowId) Storage.get("flow_id")).getId());
                }
                message.reply(message.body());
            } catch (Throwable error) {
                remoteFailure.set(error);
                message.fail(500, "Remote removal assertion failed");
            }
        });

        consumer.completion().compose(ignored -> inFlow(sender, "caller", item -> {
            Storage.put("flow_id", original);
            assertSame(original, Storage.remove("flow_id"));
            DeliveryOptions stale = new DeliveryOptions().setSendTimeout(5_000)
                .addHeader("flow_id", codec.marshalToString(original));
            return sender.eventBus().<String>request(address, "removed", stale).compose(reply -> {
                assertEquals("removed", reply.body());
                assertNull(Storage.get("flow_id"));
                FlowId returned = (FlowId) codec.unmarshalFormString(reply.headers().get("flow_id"));
                assertEquals("peer", returned.getId());
                Storage.put("flow_id", readded);
                return sender.eventBus().<String>request(address, "re-added", stale);
            }).map(reply -> {
                assertEquals("re-added", reply.body());
                assertSame(readded, Storage.get("flow_id"));
                return reply;
            });
        })).recover(error -> Future.failedFuture(remoteFailure.get() == null ? error : remoteFailure.get()))
            .eventually(consumer::unregister)
            .andThen(ignored -> sender.eventBus().removeOutboundInterceptor(selectedWriter))
            .onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @EnumSource(CodecProfile.class)
    void request_removeWhileReplyIsPending_doesNotRestorePeerValue(
            CodecProfile profile, VertxTestContext context) {
        String address = "cluster-pending-remove-" + UUID.randomUUID();
        FlowIdService codec = new FlowIdService(profile);
        Handler<DeliveryContext<Object>> selectedWriter = writer(codec);
        receiver.eventBus().addOutboundInterceptor(selectedWriter);
        FlowId original = FlowId.builder().withId("caller").build();
        AtomicReference<Throwable> remoteFailure = new AtomicReference<>();
        var consumer = receiver.eventBus().<String>consumer(address, message -> {
            try {
                assertEquals("caller", ((FlowId) Storage.get("flow_id")).getId());
                Storage.put("flow_id", FlowId.builder().withId("peer-replacement").build());
                message.reply("done");
            } catch (Throwable error) {
                remoteFailure.set(error);
                message.fail(500, "Remote pending-reply assertion failed");
            }
        });

        consumer.completion().compose(ignored -> inFlow(sender, "caller", item -> {
            Storage.put("flow_id", original);
            var pending = sender.eventBus().<String>request(address, "request",
                new DeliveryOptions().setSendTimeout(5_000));
            assertSame(original, Storage.remove("flow_id"));
            return pending.map(reply -> {
                assertNull(Storage.get("flow_id"));
                FlowId returned = (FlowId) codec.unmarshalFormString(reply.headers().get("flow_id"));
                assertEquals("peer-replacement", returned.getId());
                return reply;
            });
        })).recover(error -> Future.failedFuture(remoteFailure.get() == null ? error : remoteFailure.get()))
            .eventually(consumer::unregister)
            .andThen(ignored -> receiver.eventBus().removeOutboundInterceptor(selectedWriter))
            .onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @EnumSource(CodecProfile.class)
    void request_removalMarker_forwardsAcrossThreeNodesAndReturns(CodecProfile profile, VertxTestContext context) {
        String bAddress = "remove-b-" + UUID.randomUUID();
        String cAddress = "remove-c-" + UUID.randomUUID();
        FlowIdService codec = new FlowIdService(profile);
        AtomicReference<Throwable> remoteFailure = new AtomicReference<>();
        var cConsumer = downstream.eventBus().<String>consumer(cAddress, message -> {
            try {
                assertTrue(codec.isRemoval(message.headers().get("flow_id")));
                assertNull(Storage.get("flow_id"));
                message.reply("C");
            } catch (Throwable error) {
                remoteFailure.set(error);
                message.fail(500, "Removal forwarding assertion failed");
            }
        });
        var bConsumer = receiver.eventBus().<String>consumer(bAddress, message -> {
            try {
                assertTrue(codec.isRemoval(message.headers().get("flow_id")));
                assertNull(Storage.get("flow_id"));
                receiver.eventBus().<String>request(cAddress, "forward").onComplete(result -> {
                    try {
                        assertTrue(result.succeeded());
                        assertTrue(codec.isRemoval(result.result().headers().get("flow_id")));
                        assertNull(Storage.get("flow_id"));
                        message.reply(result.result().body());
                    } catch (Throwable error) {
                        remoteFailure.set(error);
                        message.fail(500, "Removal return assertion failed");
                    }
                });
            } catch (Throwable error) {
                remoteFailure.set(error);
                message.fail(500, "Removal forwarding assertion failed");
            }
        });
        Handler<DeliveryContext<Object>> selectedWriter = writer(codec);
        sender.eventBus().addOutboundInterceptor(selectedWriter);
        Future.all(bConsumer.completion(), cConsumer.completion()).compose(ignored -> inFlow(sender, "caller", item -> {
            Storage.remove("flow_id");
            return sender.eventBus().<String>request(bAddress, "start").map(reply -> {
                assertEquals("C", reply.body());
                assertTrue(codec.isRemoval(reply.headers().get("flow_id")));
                assertNull(Storage.get("flow_id"));
                return reply;
            });
        })).recover(error -> Future.failedFuture(remoteFailure.get() == null ? error : remoteFailure.get()))
            .eventually(() -> Future.join(bConsumer.unregister(), cConsumer.unregister()).mapEmpty())
            .andThen(ignored -> sender.eventBus().removeOutboundInterceptor(selectedWriter))
            .onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @MethodSource("removalReturnScenarios")
    void request_remoteRemoval_usesSameInboundPolicyAsPut(
            CodecProfile profile, boolean existing, VertxTestContext context) {
        String address = "remove-return-" + UUID.randomUUID();
        String checkAddress = "remove-check-" + UUID.randomUUID();
        FlowIdService codec = new FlowIdService(profile);
        FlowId original = FlowId.builder().withId("caller").build();
        AtomicReference<Throwable> remoteFailure = new AtomicReference<>();
        var remover = receiver.eventBus().<String>consumer(address, message -> {
            Storage.remove("flow_id");
            message.reply("removed");
        });
        var checker = downstream.eventBus().<String>consumer(checkAddress, message -> {
            try {
                if (existing) {
                    assertEquals(original.getId(), ((FlowId) Storage.get("flow_id")).getId());
                } else {
                    assertNull(Storage.get("flow_id"));
                    assertTrue(codec.isRemoval(message.headers().get("flow_id")));
                }
                message.reply("checked");
            } catch (Throwable error) {
                remoteFailure.set(error);
                message.fail(500, "Removal policy assertion failed");
            }
        });
        Handler<DeliveryContext<Object>> selectedWriter = writer(codec);
        receiver.eventBus().addOutboundInterceptor(selectedWriter);
        Future.all(remover.completion(), checker.completion()).compose(ignored -> inFlow(sender, "caller", item -> {
            if (existing) {
                Storage.put("flow_id", original);
            }
            return sender.eventBus().<String>request(address, "remove").compose(reply -> {
                assertTrue(codec.isRemoval(reply.headers().get("flow_id")));
                if (existing) {
                    assertSame(original, Storage.get("flow_id"));
                } else {
                    assertNull(Storage.get("flow_id"));
                }
                return sender.eventBus().<String>request(checkAddress, "check");
            }).map(reply -> {
                assertEquals("checked", reply.body());
                return reply;
            });
        })).recover(error -> Future.failedFuture(remoteFailure.get() == null ? error : remoteFailure.get()))
            .eventually(() -> Future.join(remover.unregister(), checker.unregister()).mapEmpty())
            .andThen(ignored -> receiver.eventBus().removeOutboundInterceptor(selectedWriter))
            .onComplete(context.succeedingThenComplete());
    }

    private static Stream<Arguments> removalReturnScenarios() {
        return Arrays.stream(CodecProfile.values())
            .flatMap(profile -> Stream.of(false, true).map(existing -> Arguments.of(profile, existing)));
    }

    private static Handler<DeliveryContext<Object>> writer(FlowIdService codec) {
        return new EventBusTransportHandler(() -> List.of(codec))::handleOutbound;
    }

    private static Stream<Arguments> transports() {
        return Arrays.stream(CodecProfile.values()).flatMap(sender -> Arrays.stream(CodecProfile.values())
            .flatMap(receiver -> Arrays.stream(ReturnAction.values()).map(action -> Arguments.of(sender, receiver, action))));
    }
}
