package com.inqwise.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.Context;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.spi.context.storage.ContextLocal;
import io.vertx.core.spi.tracing.SpanKind;
import io.vertx.core.spi.tracing.TagExtractor;
import io.vertx.core.spi.tracing.VertxTracer;
import io.vertx.core.tracing.TracingPolicy;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.impl.KafkaConsumerRecordImpl;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import io.vertx.kafka.client.producer.impl.KafkaHeaderImpl;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(10)
class KafkaContextTransportTest {
    private static final ContextLocal<Object> foreignLocal = ContextLocal.registerLocal(Object.class);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sender_nullManagedHeader_stripsStaleMetadataWithoutMutatingInput(
            boolean allowed, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        MockProducer<String, String> nativeProducer = mockProducer();
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, nativeProducer);
        var sender = KafkaContextTransport.sender(producer, "orders");
        inFlow(vertx, "caller", ignored -> {
            ContextInternal caller = ContextInternal.current();
            FlowId original = FlowId.builder().withId("caller").build();
            Storage.put("flow_id", original);
            KafkaProducerRecord<String, String> input = KafkaProducerRecord.create(allowed ? "orders" : "external", "key", "payload");
            input.addHeader("application", "retained");
            input.addHeader(KafkaContextCarrier.name("flow_id"), (Buffer) null);
            return sender.send(input).compose(ack -> {
                assertSame(caller, ContextInternal.current());
                assertSame(original, Storage.get("flow_id"));
                assertEquals(input.topic(), ack.getTopic());
                assertEquals(2, input.headers().size());
                assertEquals("retained", input.headers().getFirst().value().toString());
                assertNull(input.headers().getLast().value());
                ProducerRecord<String, String> sent = nativeProducer.history().getFirst();
                assertEquals("payload", sent.value());
                assertEquals("retained", new String(sent.headers().lastHeader("application").value(),
                    java.nio.charset.StandardCharsets.UTF_8));
                var metadata = new KafkaContextCarrier(() -> consumed(sent, 0).headers()).getAll("flow_id");
                if (allowed) {
                    assertEquals(original.toJson(), ((FlowId) new FlowIdService().unmarshalFormString(metadata.getFirst())).toJson());
                } else {
                    assertTrue(metadata.isEmpty());
                }
                return vertx.timer(1).map(tick -> {
                    assertSame(caller, ContextInternal.current());
                    assertSame(original, Storage.get("flow_id"));
                    return tick;
                });
            });
        }).eventually(producer::close).onComplete(context.succeedingThenComplete());
    }

    @Test
    void sender_nullApplicationHeader_preservesNativeFailureAndAllowsNextSend(Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        MockProducer<String, String> nativeProducer = mockProducer();
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, nativeProducer);
        var sender = KafkaContextTransport.sender(producer, "orders");
        inFlow(vertx, "caller", ignored -> {
            ContextInternal caller = ContextInternal.current();
            FlowId original = FlowId.builder().withId("caller").build();
            Storage.put("flow_id", original);
            KafkaProducerRecord<String, String> input = KafkaProducerRecord.create("orders", "key", "payload");
            input.addHeader("application", (Buffer) null);
            RuntimeException nativeFailure = assertThrows(RuntimeException.class, () -> producer.send(input));
            return sender.send(input).transform(result -> {
                assertTrue(result.failed());
                assertEquals(nativeFailure.getClass(), result.cause().getClass());
                assertEquals(nativeFailure.getMessage(), result.cause().getMessage());
                assertSame(caller, ContextInternal.current());
                assertSame(original, Storage.get("flow_id"));
                assertEquals(1, input.headers().size());
                assertNull(input.headers().getFirst().value());
                assertTrue(nativeProducer.history().isEmpty());
                return vertx.timer(1);
            }).compose(tick -> {
                assertSame(caller, ContextInternal.current());
                assertSame(original, Storage.get("flow_id"));
                return sender.send(KafkaProducerRecord.create("orders", "next", "delivered"));
            }).map(ack -> {
                assertSame(original, Storage.get("flow_id"));
                assertEquals("delivered", nativeProducer.history().getFirst().value());
                return ack;
            });
        }).eventually(producer::close).onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @MethodSource("synchronousFailureModes")
    void sender_synchronousRecordFailure_returnsCallerFutureAndAllowsNextSend(
            ThreadingModel model, String state, boolean headerFailure, Vertx vertx, VertxTestContext testContext) {
        vertx.exceptionHandler(testContext::failNow);
        MockProducer<String, String> nativeProducer = mockProducer();
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, nativeProducer);
        var sender = KafkaContextTransport.sender(producer, "orders");
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                ContextInternal caller = ContextInternal.current();
                FlowId original = FlowId.builder().withId("caller").build();
                if (state.equals("value")) {
                    Storage.put("flow_id", original);
                } else if (state.equals("removed")) {
                    Storage.remove("flow_id");
                }
                Runnable assertState = () -> {
                    assertSame(caller, ContextInternal.current());
                    assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                    assertSame(state.equals("value") ? original : null, Storage.get("flow_id"));
                    assertEquals(state.equals("removed"), DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                };
                Buffer application = Buffer.buffer("retained");
                KafkaProducerRecord<String, String> record = KafkaProducerRecord.create("orders", "invalid", "payload",
                    headerFailure ? 0 : -1);
                record.addHeader("application", application);
                record.addHeader(KafkaContextCarrier.name("flow_id"), "stale-managed-header");
                IllegalStateException expected = new IllegalStateException("Controlled header accessor failure");
                if (headerFailure) {
                    record.addHeader(new KafkaHeaderImpl("broken", "unavailable") {
                        @Override
                        public Buffer value() {
                            throw expected;
                        }
                    });
                }
                RuntimeException nativeFailure = assertThrows(RuntimeException.class, () -> producer.send(record));
                Future<io.vertx.kafka.client.producer.RecordMetadata> failed = assertDoesNotThrow(() -> sender.send(record));
                return failed.transform(result -> {
                    assertTrue(result.failed());
                    if (headerFailure) {
                        assertSame(expected, nativeFailure);
                        assertSame(expected, result.cause());
                    } else {
                        assertInstanceOf(IllegalArgumentException.class, result.cause());
                        assertEquals(nativeFailure.getClass(), result.cause().getClass());
                        assertEquals(nativeFailure.getMessage(), result.cause().getMessage());
                    }
                    assertState.run();
                    assertTrue(nativeProducer.history().isEmpty());
                    assertEquals(headerFailure ? 3 : 2, record.headers().size());
                    assertSame(application, record.headers().getFirst().value());
                    assertEquals("retained", application.toString());
                    assertEquals("stale-managed-header", record.headers().get(1).value().toString());
                    return vertx.timer(1);
                }).compose(tick -> {
                    assertState.run();
                    return sender.send(KafkaProducerRecord.<String, String>create("orders", "valid", "payload")
                        .addHeader("application", application));
                }).map(ack -> {
                    assertState.run();
                    assertEquals("orders", ack.getTopic());
                    assertEquals(1, nativeProducer.history().size());
                    ProducerRecord<String, String> sent = nativeProducer.history().getFirst();
                    assertEquals("valid", sent.key());
                    assertEquals("payload", sent.value());
                    var exported = new KafkaContextCarrier(() -> consumed(sent, 0).headers()).getAll("flow_id");
                    FlowIdService codec = new FlowIdService();
                    if (state.equals("empty")) {
                        assertTrue(exported.isEmpty());
                    } else if (state.equals("removed")) {
                        assertTrue(codec.isRemoval(exported.getFirst()));
                    } else {
                        assertEquals(original.toJson(), ((FlowId) codec.unmarshalFormString(exported.getFirst())).toJson());
                    }
                    return ack;
                });
            }
        }, new DeploymentOptions().setThreadingModel(model)).eventually(producer::close)
            .onComplete(testContext.succeedingThenComplete());
    }

    private static Stream<Arguments> synchronousFailureModes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of("value", "removed", "empty").flatMap(state -> Stream.of(false, true)
                .map(header -> Arguments.of(model, state, header))));
    }

    @ParameterizedTest
    @MethodSource("nestedHandlerModes")
    void handler_nestedDispatch_restoresEachScopeAndRetainsAsyncRecordState(
            ThreadingModel model, boolean innerRemoval, String outcome, boolean innerAllowed, boolean outerAllowed,
            Vertx vertx, VertxTestContext testContext) {
        vertx.exceptionHandler(testContext::failNow);
        Throwable failure = switch (outcome) {
            case "runtime" -> new IllegalStateException("Controlled nested record failure");
            case "assertion" -> new AssertionError("Controlled nested record error");
            default -> null;
        };
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                ContextInternal parent = ContextInternal.current();
                FlowId original = FlowId.builder().withId("parent").build();
                FlowId parentPrivate = FlowId.builder().withId("parent-private").build();
                Storage.put("flow_id", original);
                Storage.put("private-item", parentPrivate);
                Promise<Void> unwound = Promise.promise();
                List<Future<Void>> timers = new ArrayList<>();
                List<ContextInternal> scopes = new ArrayList<>();
                Object outerForeign = new Object();
                AtomicReference<Handler<KafkaConsumerRecord<String, String>>> handler = new AtomicReference<>();
                FlowIdService codec = new FlowIdService();
                KafkaProducerRecord<String, String> inner = KafkaProducerRecord.create(
                    innerAllowed ? "orders" : "restricted", "inner", "payload");
                if (!innerAllowed && !innerRemoval) {
                    inner.addHeader(KafkaContextCarrier.name("flow_id"), "not-a-valid-envelope");
                } else {
                    new KafkaContextCarrier(inner::headers).set("flow_id", innerRemoval ? codec.marshalRemovalToString()
                        : codec.marshalToString(FlowId.builder().withId("incoming-inner").build()));
                }
                handler.set(KafkaContextTransport.handler(message -> {
                    ContextInternal scope = ContextInternal.current();
                    assertNotSame(parent, scope);
                    assertEquals(model, scope.threadingModel());
                    assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                    scopes.forEach(previous -> assertNotSame(previous, scope));
                    scopes.add(scope);
                    assertEquals("payload", message.value());
                    assertNull(Storage.get("private-item"));
                    if (message.key().equals("outer")) {
                        assertEquals(outerAllowed ? "orders" : "restricted", message.topic());
                        if (outerAllowed) {
                            assertEquals("incoming-outer", ((FlowId) Storage.get("flow_id")).getId());
                        } else {
                            assertNull(Storage.get("flow_id"));
                            assertFalse(DefaultStorage.isRemoved(DefaultStorage.getEntry(scope, "flow_id")));
                        }
                    } else if (message.key().equals("inner")) {
                        assertSame(outerForeign, scope.getLocal(foreignLocal,
                            io.vertx.core.spi.context.storage.AccessMode.CONCURRENT));
                        assertEquals(innerAllowed ? "orders" : "restricted", message.topic());
                        if (!innerAllowed) {
                            assertNull(Storage.get("flow_id"));
                            assertFalse(DefaultStorage.isRemoved(DefaultStorage.getEntry(scope, "flow_id")));
                        } else if (innerRemoval) {
                            assertNull(Storage.get("flow_id"));
                            assertTrue(DefaultStorage.isRemoved(DefaultStorage.getEntry(scope, "flow_id")));
                        } else {
                            assertEquals("incoming-inner", ((FlowId) Storage.get("flow_id")).getId());
                        }
                    } else {
                        assertNull(Storage.get("flow_id"));
                        assertFalse(DefaultStorage.isRemoved(DefaultStorage.getEntry(scope, "flow_id")));
                    }
                    FlowId local = FlowId.builder().withId(message.key() + "-local").build();
                    FlowId privateValue = FlowId.builder().withId(message.key() + "-private").build();
                    boolean removed = message.key().equals("inner") && innerRemoval;
                    if (removed) {
                        Storage.remove("flow_id");
                    } else {
                        Storage.put("flow_id", local);
                    }
                    Storage.put("private-item", privateValue);
                    Object localForeign = message.key().equals("outer") ? outerForeign : new Object();
                    scope.putLocal(foreignLocal, io.vertx.core.spi.context.storage.AccessMode.CONCURRENT, localForeign);
                    timers.add(vertx.timer(1).compose(tick -> {
                        assertSame(scope, ContextInternal.current());
                        return ContextPropagation.bind(unwound.future());
                    }).<Void>map(ignored -> {
                        assertSame(scope, ContextInternal.current());
                        assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                        assertSame(removed ? null : local, Storage.get("flow_id"));
                        assertEquals(removed, DefaultStorage.isRemoved(DefaultStorage.getEntry(scope, "flow_id")));
                        assertSame(privateValue, Storage.get("private-item"));
                        assertSame(localForeign, scope.getLocal(foreignLocal,
                            io.vertx.core.spi.context.storage.AccessMode.CONCURRENT));
                        return null;
                    }));
                    if (message.key().equals("outer")) {
                        if (failure == null) {
                            handler.get().handle(consumed(inner.record(), 1));
                        } else {
                            assertSame(failure, assertThrows(Throwable.class,
                                () -> handler.get().handle(consumed(inner.record(), 1))));
                        }
                        assertSame(scope, ContextInternal.current());
                        assertSame(local, Storage.get("flow_id"));
                        assertSame(privateValue, Storage.get("private-item"));
                        assertSame(outerForeign, scope.getLocal(foreignLocal,
                            io.vertx.core.spi.context.storage.AccessMode.CONCURRENT));
                    } else if (message.key().equals("inner") && failure != null) {
                        if (failure instanceof Error error) {
                            throw error;
                        }
                        throw (RuntimeException) failure;
                    }
                }, "orders"));
                KafkaProducerRecord<String, String> outer = KafkaProducerRecord.create(
                    outerAllowed ? "orders" : "restricted", "outer", "payload");
                if (outerAllowed) {
                    new KafkaContextCarrier(outer::headers).set("flow_id", codec.marshalToString(
                        FlowId.builder().withId("incoming-outer").build()));
                } else {
                    outer.addHeader(KafkaContextCarrier.name("flow_id"), "not-a-valid-envelope");
                }
                handler.get().handle(consumed(outer.record(), 0));
                assertSame(parent, ContextInternal.current());
                assertSame(original, Storage.get("flow_id"));
                assertSame(parentPrivate, Storage.get("private-item"));
                handler.get().handle(consumed(KafkaProducerRecord.create("orders", "next", "payload").record(), 2));
                assertEquals(3, scopes.size());
                assertSame(parent, ContextInternal.current());
                assertSame(original, Storage.get("flow_id"));
                assertSame(parentPrivate, Storage.get("private-item"));
                assertNull(parent.getLocal(foreignLocal, io.vertx.core.spi.context.storage.AccessMode.CONCURRENT));
                unwound.complete();
                return ContextPropagation.bind(Future.join(timers)).map(ignored -> {
                    assertSame(parent, ContextInternal.current());
                    assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                    assertSame(original, Storage.get("flow_id"));
                    assertSame(parentPrivate, Storage.get("private-item"));
                    return ignored;
                });
            }
        }, new DeploymentOptions().setThreadingModel(model)).onComplete(testContext.succeedingThenComplete());
    }

    private static Stream<Arguments> nestedHandlerModes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of(false, true).flatMap(removal -> Stream.of("success", "runtime", "assertion")
                .flatMap(outcome -> Stream.of(false, true)
                    .flatMap(innerAllowed -> Stream.of(false, true)
                        .map(outerAllowed -> Arguments.of(model, removal, outcome, innerAllowed, outerAllowed))))));
    }

    @ParameterizedTest
    @MethodSource("senderMutationModes")
    void sender_concurrentCallersAndReversedAcknowledgments_preserveSendSnapshotAndCurrentStorage(
            ThreadingModel model, boolean removal, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        Map<String, Runnable> acknowledgments = new ConcurrentHashMap<>();
        Promise<Void> submitted = Promise.promise();
        Promise<Void> callersReady = Promise.promise();
        AtomicInteger readyCount = new AtomicInteger();
        KafkaException rejected = new KafkaException("Controlled first-caller rejection");
        MockProducer<String, String> nativeProducer = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer()) {
            @Override
            public synchronized java.util.concurrent.Future<RecordMetadata> send(
                    ProducerRecord<String, String> record, Callback callback) {
                return super.send(record, (metadata, failure) -> {
                    acknowledgments.put(record.key(), () -> callback.onCompletion(metadata,
                        record.key().equals("first") ? rejected : failure));
                    if (acknowledgments.size() == 2) {
                        submitted.tryComplete();
                    }
                });
            }
        };
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, nativeProducer);
        Promise<Throwable> reported = Promise.promise();
        producer.exceptionHandler(reported::tryComplete);
        var sender = KafkaContextTransport.sender(producer, "orders");
        List<String> completed = new CopyOnWriteArrayList<>();
        List<Future<String>> deployments = new ArrayList<>();
        for (String id : List.of("first", "second")) {
            deployments.add(vertx.deployVerticle(new VerticleBase() {
                @Override
                public Future<?> start() {
                    ContextInternal caller = ContextInternal.current();
                    assertEquals(model, caller.threadingModel());
                    assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                    FlowId sent = FlowId.builder().withId(id + "-sent").build();
                    FlowId current = FlowId.builder().withId(id + "-current").build();
                    Storage.put("flow_id", sent);
                    KafkaProducerRecord<String, String> record = KafkaProducerRecord.create("orders", id, "payload");
                    Future<io.vertx.kafka.client.producer.RecordMetadata> pending = sender.send(record);
                    assertFalse(pending.isComplete());
                    if (removal) {
                        Storage.remove("flow_id");
                    } else {
                        Storage.put("flow_id", current);
                    }
                    if (readyCount.incrementAndGet() == 2) {
                        callersReady.complete();
                    }
                    return pending.transform(result -> {
                        assertSame(caller, ContextInternal.current());
                        assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                        assertEquals(id.equals("first"), result.failed());
                        if (result.failed()) {
                            assertSame(rejected, result.cause());
                        } else {
                            assertEquals("orders", result.result().getTopic());
                        }
                        if (removal) {
                            assertNull(Storage.get("flow_id"));
                            assertTrue(DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                        } else {
                            assertSame(current, Storage.get("flow_id"));
                        }
                        assertTrue(record.headers().isEmpty());
                        completed.add(id);
                        return Future.succeededFuture();
                    });
                }
            }, new DeploymentOptions().setThreadingModel(model)));
        }
        Future.join(submitted.future(), callersReady.future()).compose(ignored -> {
            assertEquals(2, nativeProducer.history().size());
            for (ProducerRecord<String, String> record : nativeProducer.history()) {
                KafkaConsumerRecord<String, String> incoming = consumed(record, 0);
                String encoded = new KafkaContextCarrier(incoming::headers).getAll("flow_id").getFirst();
                FlowId decoded = (FlowId) new FlowIdService().unmarshalFormString(encoded);
                assertEquals(record.key() + "-sent", decoded.getId());
            }
            acknowledgments.get("second").run();
            return deployments.get(1);
        }).compose(ignored -> {
            assertEquals(List.of("second"), completed);
            acknowledgments.get("first").run();
            return Future.join(deployments).compose(done -> reported.future());
        }).map(ignored -> {
            assertSame(rejected, ignored);
            assertEquals(List.of("second", "first"), completed);
            return ignored;
        }).eventually(producer::close).onComplete(context.succeedingThenComplete());
    }

    private static Stream<Arguments> senderMutationModes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of(false, true).map(removal -> Arguments.of(model, removal)));
    }

    @ParameterizedTest
    @MethodSource("handlerFailureModes")
    void handler_applicationFailure_reachesCallerAndRestoresParentContext(
            ThreadingModel model, boolean error, Vertx vertx, VertxTestContext context) {
        List<Throwable> reported = new CopyOnWriteArrayList<>();
        vertx.exceptionHandler(reported::add);
        Throwable failure = error ? new AssertionError("Controlled application error")
            : new IllegalStateException("Controlled application failure");
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                ContextInternal parent = ContextInternal.current();
                assertEquals(model, parent.threadingModel());
                assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                FlowId original = FlowId.builder().withId("parent").build();
                Storage.put("flow_id", original);
                List<ContextInternal> scopes = new ArrayList<>();
                Promise<Void> timerCompleted = Promise.promise();
                Handler<KafkaConsumerRecord<String, String>> handler = KafkaContextTransport.handler(message -> {
                    ContextInternal scope = ContextInternal.current();
                    assertEquals(model, scope.threadingModel());
                    assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                    assertNotSame(parent, scope);
                    scopes.add(scope);
                    if (message.key().equals("failed")) {
                        assertEquals("incoming", ((FlowId) Storage.get("flow_id")).getId());
                        FlowId changed = FlowId.builder().withId("failed-record-local").build();
                        Storage.put("flow_id", changed);
                        vertx.timer(1).onComplete(result -> {
                            try {
                                assertTrue(result.succeeded());
                                assertSame(scope, ContextInternal.current());
                                assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                                assertSame(changed, Storage.get("flow_id"));
                                timerCompleted.complete();
                            } catch (Throwable assertion) {
                                timerCompleted.tryFail(assertion);
                            }
                        });
                        if (failure instanceof Error applicationError) {
                            throw applicationError;
                        }
                        throw (RuntimeException) failure;
                    }
                    assertNotSame(scopes.getFirst(), scope);
                    assertNull(Storage.get("flow_id"));
                    List<io.vertx.kafka.client.producer.KafkaHeader> exported = new ArrayList<>();
                    ContextTransport.writeTo(new KafkaContextCarrier(() -> exported));
                    assertTrue(exported.isEmpty());
                    Storage.remove("flow_id");
                }, "orders");
                KafkaProducerRecord<String, String> failed = KafkaProducerRecord.create("orders", "failed", "payload");
                new KafkaContextCarrier(failed::headers).set("flow_id", new FlowIdService()
                    .marshalToString(FlowId.builder().withId("incoming").build()));

                assertSame(failure, assertThrows(Throwable.class, () -> handler.handle(consumed(failed.record(), 0))));
                assertSame(parent, ContextInternal.current());
                assertSame(original, Storage.get("flow_id"));
                handler.handle(consumed(KafkaProducerRecord.create("orders", "next", "payload").record(), 1));
                assertEquals(2, scopes.size());
                assertSame(parent, ContextInternal.current());
                assertSame(original, Storage.get("flow_id"));
                assertTrue(reported.isEmpty());
                return ContextPropagation.bind(timerCompleted.future()).map(ignored -> {
                    assertSame(parent, ContextInternal.current());
                    assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                    assertSame(original, Storage.get("flow_id"));
                    assertFalse(DefaultStorage.isRemoved(DefaultStorage.getEntry(parent, "flow_id")));
                    return ignored;
                });
            }
        }, new DeploymentOptions().setThreadingModel(model)).onComplete(context.succeedingThenComplete());
    }

    private static Stream<Arguments> handlerFailureModes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of(false, true).map(error -> Arguments.of(model, error)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void streamingConsumer_applicationFailure_reachesNativeTracerAndExceptionHandler(
            boolean error, VertxTestContext context) {
        Throwable failure = error ? new AssertionError("Controlled application error")
            : new IllegalStateException("Controlled application failure");
        Promise<Throwable> traced = Promise.promise();
        Promise<Throwable> reported = Promise.promise();
        VertxTracer<Object, Object> tracer = new VertxTracer<>() {
            @Override
            public <R> Object receiveRequest(Context current, SpanKind kind, TracingPolicy policy, R request,
                    String operation, Iterable<Map.Entry<String, String>> headers, TagExtractor<R> tags) {
                return new Object();
            }

            @Override
            public <R> void sendResponse(Context current, R response, Object span, Throwable cause, TagExtractor<R> tags) {
                traced.tryComplete(cause);
            }
        };
        Vertx tracingVertx = Vertx.builder().withTracer(options -> tracer).build();
        tracingVertx.exceptionHandler(reported::tryComplete);
        MockConsumer<String, String> nativeConsumer = new MockConsumer<>("earliest");
        nativeConsumer.updateBeginningOffsets(Map.of(new TopicPartition("orders", 0), 0L));
        KafkaConsumer<String, String> consumer = KafkaConsumer.create(tracingVertx, nativeConsumer);
        consumer.exceptionHandler(context::failNow);
        KafkaProducerRecord<String, String> record = KafkaProducerRecord.create("orders", "key", "payload");
        new KafkaContextCarrier(record::headers).set("flow_id", new FlowIdService()
            .marshalToString(FlowId.builder().withId("incoming").build()));
        consumer.assign(new io.vertx.kafka.client.common.TopicPartition("orders", 0)).compose(assigned -> {
            consumer.handler(KafkaContextTransport.handler(message -> {
                assertEquals("incoming", ((FlowId) Storage.get("flow_id")).getId());
                if (failure instanceof Error applicationError) {
                    throw applicationError;
                }
                throw (RuntimeException) failure;
            }, "orders"));
            nativeConsumer.schedulePollTask(() -> nativeConsumer.addRecord(consumed(record.record(), 0).record()));
            return Future.join(traced.future(), reported.future());
        }).map(done -> {
            assertSame(failure, traced.future().result());
            assertSame(failure, reported.future().result());
            return done;
        }).eventually(consumer::close).eventually(tracingVertx::close)
            .onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sender_recordHandler_carryValueOrRemovalWithoutReturningConsumerChanges(
            boolean removal, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        MockProducer<String, String> nativeProducer = mockProducer();
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, nativeProducer);
        var sender = KafkaContextTransport.sender(producer, "orders", "forwarded");
        inFlow(vertx, "caller", ignored -> {
            FlowId original = FlowId.builder().withId("producer:\u0394").build();
            Storage.put("flow_id", original);
            if (removal) {
                Storage.remove("flow_id");
            }
            KafkaProducerRecord<String, String> record = KafkaProducerRecord.create("orders", "key", "value", 123L, 0);
            record.addHeader("application", "retained").addHeader(KafkaContextCarrier.name("flow_id"), "stale");
            return sender.send(record).compose(ack -> {
                assertEquals("orders", ack.getTopic());
                ProducerRecord<String, String> sent = nativeProducer.history().getFirst();
                assertEquals(123L, sent.timestamp());
                assertEquals(0, sent.partition());
                assertEquals("retained", new String(sent.headers().lastHeader("application").value(), java.nio.charset.StandardCharsets.UTF_8));
                assertEquals("stale", record.headers().getLast().value().toString());
                Promise<Void> processed = Promise.promise();
                Handler<KafkaConsumerRecord<String, String>> handler = KafkaContextTransport.handler(message -> {
                    try {
                        assertNull(Storage.get(ClientContextTestSupport.KEY));
                        if (removal) {
                            assertNull(Storage.get("flow_id"));
                            assertTrue(new FlowIdService().isRemoval(new KafkaContextCarrier(message::headers)
                                .getAll("flow_id").getFirst()));
                        } else {
                            FlowId received = (FlowId) Storage.get("flow_id");
                            assertNotSame(original, received);
                            assertEquals(original.toJson(), received.toJson());
                        }
                        FlowId changed = FlowId.builder().withId("consumer").build();
                        Storage.put("flow_id", changed);
                        vertx.timer(1).compose(tick -> {
                            assertSame(changed, Storage.get("flow_id"));
                            return sender.send(KafkaProducerRecord.create("forwarded", "key", "derived"));
                        }).onComplete(result -> {
                            if (result.failed()) {
                                processed.tryFail(result.cause());
                            } else {
                                try {
                                    assertSame(changed, Storage.get("flow_id"));
                                    processed.complete();
                                } catch (Throwable failure) {
                                    processed.tryFail(failure);
                                }
                            }
                        });
                    } catch (Throwable failure) {
                        processed.tryFail(failure);
                    }
                }, "orders");
                handler.handle(consumed(sent, 0));
                return ContextPropagation.bind(processed.future());
            }).map(done -> {
                if (removal) {
                    assertNull(Storage.get("flow_id"));
                } else {
                    assertSame(original, Storage.get("flow_id"));
                }
                ProducerRecord<String, String> forwarded = nativeProducer.history().getLast();
                String encoded = new KafkaContextCarrier(() -> consumed(forwarded, 1).headers()).getAll("flow_id").getFirst();
                assertEquals("consumer", ((FlowId) new FlowIdService().unmarshalFormString(encoded)).getId());
                return done;
            });
        }).eventually(producer::close).onComplete(context.succeedingThenComplete());
    }

    @Test
    void sender_reusedRecordAndOverlappingCalls_doNotMixHeaders(Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        AtomicInteger count = new AtomicInteger();
        MockProducer<String, String> delayed = new MockProducer<>(false, null, new StringSerializer(), new StringSerializer()) {
            @Override
            public synchronized java.util.concurrent.Future<RecordMetadata> send(ProducerRecord<String, String> record, Callback callback) {
                var result = super.send(record, callback);
                if (count.incrementAndGet() == 2) {
                    completeNext();
                    completeNext();
                }
                return result;
            }
        };
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, delayed);
        var sender = KafkaContextTransport.sender(producer, "orders");
        KafkaProducerRecord<String, String> shared = KafkaProducerRecord.create("orders", "key", "value");
        shared.addHeader("application", "original");
        List<Future<Void>> calls = new ArrayList<>();
        for (String id : List.of("first", "second")) {
            calls.add(inFlow(vertx, id, ignored -> {
                FlowId original = FlowId.builder().withId(id).build();
                Storage.put("flow_id", original);
                return sender.send(shared).map(ack -> {
                    assertSame(original, Storage.get("flow_id"));
                    return ack;
                });
            }));
        }
        Future.join(calls).map(ignored -> {
            List<String> ids = delayed.history().stream().map(record -> {
                String encoded = new KafkaContextCarrier(() -> consumed(record, 0).headers()).getAll("flow_id").getFirst();
                return ((FlowId) new FlowIdService().unmarshalFormString(encoded)).getId();
            }).sorted().toList();
            assertEquals(List.of("first", "second"), ids);
            assertEquals(1, shared.headers().size());
            return ignored;
        }).eventually(producer::close).onComplete(context.succeedingThenComplete());
    }

    @Test
    void handler_overlappingRecordsAndMissingMetadata_useIndependentContexts(Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        Promise<Void> processed = Promise.promise();
        AtomicInteger completed = new AtomicInteger();
        List<ContextInternal> scopes = new ArrayList<>();
        Object foreign = new Object();
        Handler<KafkaConsumerRecord<String, String>> handler = KafkaContextTransport.handler(message -> context.verify(() -> {
            ContextInternal scope = ContextInternal.current();
            assertSame(foreign, scope.getLocal(foreignLocal, io.vertx.core.spi.context.storage.AccessMode.CONCURRENT));
            scopes.add(scope);
            FlowId incoming = (FlowId) Storage.get("flow_id");
            if (message.key().equals("empty")) {
                assertNull(incoming);
            } else {
                assertEquals(message.key(), incoming.getId());
            }
            FlowId local = FlowId.builder().withId("local-" + message.key()).build();
            Storage.put("flow_id", local);
            vertx.timer(5).onSuccess(tick -> context.verify(() -> {
                assertSame(scope, ContextInternal.current());
                assertSame(local, Storage.get("flow_id"));
                if (completed.incrementAndGet() == 3) {
                    assertEquals(3, scopes.stream().distinct().count());
                    processed.complete();
                }
            }));
        }), "orders");
        inFlow(vertx, "parent", ignored -> {
            FlowId parent = FlowId.builder().withId("parent").build();
            Storage.put("flow_id", parent);
            ContextInternal.current().putLocal(foreignLocal, io.vertx.core.spi.context.storage.AccessMode.CONCURRENT, foreign);
            for (String id : List.of("first", "second", "empty")) {
                KafkaProducerRecord<String, String> record = KafkaProducerRecord.create("orders", id, "value");
                if (!id.equals("empty")) {
                    new KafkaContextCarrier(record::headers).set("flow_id", new FlowIdService()
                        .marshalToString(FlowId.builder().withId(id).build()));
                }
                handler.handle(consumed(record.record(), 0));
                assertSame(parent, Storage.get("flow_id"));
            }
            return ContextPropagation.bind(processed.future()).map(done -> {
                assertSame(parent, Storage.get("flow_id"));
                return done;
            });
        }).onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sender_submissionFailure_preservesCaller(boolean fail, Vertx vertx, VertxTestContext context) {
        MockProducer<String, String> nativeProducer = mockProducer();
        KafkaException failure = new KafkaException("Controlled submission failure");
        if (fail) {
            nativeProducer.sendException = failure;
        }
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, nativeProducer);
        var sender = KafkaContextTransport.sender(producer, "orders");
        inFlow(vertx, "caller", ignored -> {
            FlowId original = FlowId.builder().withId("caller").build();
            Storage.put("flow_id", original);
            return sender.send(KafkaProducerRecord.create("orders", "key", "value")).transform(result -> {
                assertEquals(fail, result.failed());
                assertSame(original, Storage.get("flow_id"));
                if (fail) {
                    assertSame(failure, result.cause());
                }
                return Future.succeededFuture();
            });
        }).eventually(producer::close).onComplete(context.succeedingThenComplete());
    }

    @Test
    void deniedTopics_deliverPayloadWithoutImportOrExport(Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        MockProducer<String, String> nativeProducer = mockProducer();
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, nativeProducer);
        var sender = KafkaContextTransport.sender(producer);
        inFlow(vertx, "caller", ignored -> {
            Storage.put("flow_id", FlowId.builder().withId("private").build());
            KafkaProducerRecord<String, String> record = KafkaProducerRecord.create("orders", "key", "value");
            new KafkaContextCarrier(record::headers).set("flow_id", new FlowIdService().marshalRemovalToString());
            return sender.send(record).map(ack -> {
                ProducerRecord<String, String> sent = nativeProducer.history().getFirst();
                assertNull(sent.headers().lastHeader(KafkaContextCarrier.name("flow_id")));
                assertEquals("value", sent.value());
                AtomicInteger delivered = new AtomicInteger();
                Handler<KafkaConsumerRecord<String, String>> handler = KafkaContextTransport.handler(message -> {
                    assertNull(Storage.get("flow_id"));
                    delivered.incrementAndGet();
                });
                handler.handle(consumed(record.record(), 0));
                assertEquals(1, delivered.get());
                return ack;
            });
        }).eventually(producer::close).onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedMetadata_deliversRecordEvenIfObserverThrows(boolean observerThrows, Vertx vertx, VertxTestContext context) {
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        vertx.exceptionHandler(failure -> {
            failures.add(failure);
            if (observerThrows) {
                throw new IllegalStateException("Observer failed");
            }
        });
        vertx.runOnContext(ignored -> context.verify(() -> {
            KafkaProducerRecord<String, String> record = KafkaProducerRecord.create("orders", "key", "value");
            record.addHeader(KafkaContextCarrier.name("flow_id"), "1:invalid-codec-data");
            AtomicInteger delivered = new AtomicInteger();
            Handler<KafkaConsumerRecord<String, String>> handler = KafkaContextTransport.handler(message -> {
                assertNull(Storage.get("flow_id"));
                assertEquals("value", message.value());
                delivered.incrementAndGet();
            }, "orders");
            handler.handle(consumed(record.record(), 0));
            assertEquals(1, delivered.get());
            assertEquals(1, failures.size());
            assertInstanceOf(ContextPropagationException.class, failures.getFirst());
            context.completeNow();
        }));
    }

    @Test
    void streamingConsumer_adapterImportsHeadersFromPolledRecord(Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        MockProducer<String, String> nativeProducer = mockProducer();
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, nativeProducer);
        MockConsumer<String, String> nativeConsumer = new MockConsumer<>("earliest");
        nativeConsumer.updateBeginningOffsets(Map.of(new TopicPartition("orders", 0), 0L));
        KafkaConsumer<String, String> consumer = KafkaConsumer.create(vertx, nativeConsumer);
        consumer.exceptionHandler(context::failNow);
        var sender = KafkaContextTransport.sender(producer, "orders");
        Promise<Void> processed = Promise.promise();
        inFlow(vertx, "producer", ignored -> {
            FlowId original = FlowId.builder().withId("producer").build();
            Storage.put("flow_id", original);
            return sender.send(KafkaProducerRecord.create("orders", "key", "value", 0))
                .compose(ack -> ContextPropagation.bind(consumer.assign(new io.vertx.kafka.client.common.TopicPartition("orders", 0))))
                .compose(assigned -> {
                    consumer.handler(KafkaContextTransport.handler(message -> {
                        try {
                            FlowId received = (FlowId) Storage.get("flow_id");
                            assertNotSame(original, received);
                            assertEquals(original.toJson(), received.toJson());
                            assertNull(Storage.get(ClientContextTestSupport.KEY));
                            vertx.timer(1).onComplete(timer -> {
                                try {
                                    assertSame(received, Storage.get("flow_id"));
                                    processed.complete();
                                } catch (Throwable failure) {
                                    processed.tryFail(failure);
                                }
                            });
                        } catch (Throwable failure) {
                            processed.tryFail(failure);
                        }
                    }, "orders"));
                    nativeConsumer.schedulePollTask(() -> nativeConsumer.addRecord(consumed(nativeProducer.history().getFirst(), 0).record()));
                    return ContextPropagation.bind(processed.future());
                }).map(done -> {
                    assertSame(original, Storage.get("flow_id"));
                    return done;
                });
        }).eventually(() -> Future.join(producer.close(), consumer.close()).mapEmpty())
            .onComplete(context.succeedingThenComplete());
    }

    @Test
    void emptyConfiguration_disablesMetadataAndInvalidUsageFailsExplicitly(Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        MockProducer<String, String> nativeProducer = mockProducer();
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, nativeProducer);
        var empty = ContextTransportConfiguration.builder().build();
        var sender = KafkaContextTransport.sender(producer, empty, "orders");
        var record = KafkaProducerRecord.create("orders", "key", "value");
        assertThrows(IllegalStateException.class, () -> sender.send(record));
        assertThrows(IllegalArgumentException.class, () -> KafkaContextTransport.sender(producer, " "));
        Handler<KafkaConsumerRecord<String, String>> handler = KafkaContextTransport.handler(empty, message -> {
            assertNull(Storage.get("flow_id"));
        }, "orders");
        assertThrows(IllegalStateException.class, () -> handler.handle(consumed(record.record(), 0)));
        inFlow(vertx, "caller", ignored -> {
            Storage.put("flow_id", FlowId.builder().withId("private").build());
            return sender.send(record).map(ack -> {
                assertNull(nativeProducer.history().getFirst().headers().lastHeader(KafkaContextCarrier.name("flow_id")));
                new KafkaContextCarrier(record::headers).set("flow_id", new FlowIdService().marshalRemovalToString());
                handler.handle(consumed(record.record(), 0));
                assertEquals("private", ((FlowId) Storage.get("flow_id")).getId());
                return ack;
            });
        }).eventually(producer::close).onComplete(context.succeedingThenComplete());
    }

    @Test
    void sender_failedAcknowledgment_returnsFailureOnCallerContext(Vertx vertx, VertxTestContext context) {
        KafkaException failure = new KafkaException("Controlled acknowledgment failure");
        MockProducer<String, String> nativeProducer = new MockProducer<>(false, null, new StringSerializer(), new StringSerializer()) {
            @Override
            public synchronized java.util.concurrent.Future<RecordMetadata> send(ProducerRecord<String, String> record, Callback callback) {
                var result = super.send(record, callback);
                errorNext(failure);
                return result;
            }
        };
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, nativeProducer);
        producer.exceptionHandler(ignored -> { });
        var sender = KafkaContextTransport.sender(producer, "orders");
        inFlow(vertx, "caller", ignored -> {
            FlowId original = FlowId.builder().withId("caller").build();
            Storage.put("flow_id", original);
            return sender.send(KafkaProducerRecord.create("orders", "key", "value")).transform(result -> {
                assertTrue(result.failed());
                assertSame(failure, result.cause());
                assertSame(original, Storage.get("flow_id"));
                return Future.succeededFuture();
            });
        }).eventually(producer::close).onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sender_oversizedMetadata_omitsHeaderWithoutFailingSend(boolean observerThrows, Vertx vertx, VertxTestContext context) {
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        vertx.exceptionHandler(failure -> {
            failures.add(failure);
            if (observerThrows) {
                throw new IllegalStateException("Controlled observer failure");
            }
        });
        MockProducer<String, String> nativeProducer = mockProducer();
        KafkaProducer<String, String> producer = KafkaProducer.create(vertx, nativeProducer);
        var sender = KafkaContextTransport.sender(producer, "orders");
        inFlow(vertx, "caller", ignored -> {
            FlowId original = FlowId.builder().withId("x".repeat(64 * 1024)).build();
            Storage.put("flow_id", original);
            return sender.send(KafkaProducerRecord.create("orders", "key", "value")).map(ack -> {
                assertSame(original, Storage.get("flow_id"));
                assertEquals("value", nativeProducer.history().getFirst().value());
                assertNull(nativeProducer.history().getFirst().headers().lastHeader(KafkaContextCarrier.name("flow_id")));
                assertEquals(1, failures.size());
                assertInstanceOf(ContextPropagationException.class, failures.getFirst());
                return ack;
            });
        }).eventually(producer::close).onComplete(context.succeedingThenComplete());
    }

    private static KafkaConsumerRecord<String, String> consumed(ProducerRecord<String, String> sent, long offset) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(sent.topic(), sent.partition() == null ? 0 : sent.partition(),
            offset, sent.key(), sent.value());
        sent.headers().forEach(header -> record.headers().add(header.key(), header.value() == null ? null : header.value().clone()));
        return new KafkaConsumerRecordImpl<>(record);
    }

    private static MockProducer<String, String> mockProducer() {
        return new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
    }
}
