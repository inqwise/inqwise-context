package com.inqwise.context;

import java.net.URI;
import java.util.List;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.streams.WriteStream;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class HttpStreamingContextTest {
    private record Scenario(String localState, boolean remoteRemoval, boolean sinkRemoval) {
    }

    @ParameterizedTest
    @MethodSource("streamModes")
    void response_streamingSink_canChangeStorageWithoutLosingCallerContext(
            ThreadingModel model, HttpVersion version, boolean multiplex, Scenario scenario, boolean closeSink,
            Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClientOptions options = new WebClientOptions().setProtocolVersion(version).setHttp2ClearTextUpgrade(false);
        options.setHttp2MultiplexImplementation(multiplex);
        WebClient client = WebClient.create(vertx, options);
        Promise<Void> sinkChanged = Promise.promise();
        Promise<Void> serverDone = Promise.promise();
        vertx.createHttpServer(new HttpServerOptions().setHttp2MultiplexImplementation(multiplex))
            .requestHandler(HttpContextTransport.handler(request -> {
                assertEquals(version, request.version());
                ContextInternal serverScope = ContextInternal.current();
                FlowId remote = FlowId.builder().withId("server-response").build();
                if (scenario.remoteRemoval()) {
                    Storage.remove("flow_id");
                } else {
                    Storage.put("flow_id", remote);
                }
                // Later chunks and the server-state check must follow the client's first mutation.
                request.response().setChunked(true).write("one-")
                    .compose(ignored -> ContextPropagation.bind(sinkChanged.future()))
                    .compose(ignored -> request.response().write("two-"))
                    .compose(ignored -> vertx.timer(1))
                    .compose(ignored -> request.response().end("three"))
                    .<Void>map(ignored -> {
                        assertSame(serverScope, ContextInternal.current());
                        if (scenario.remoteRemoval()) {
                            assertNull(Storage.get("flow_id"));
                            assertTrue(DefaultStorage.isRemoved(DefaultStorage.getEntry(serverScope, "flow_id")));
                        } else {
                            assertSame(remote, Storage.get("flow_id"));
                        }
                        return null;
                    }).onComplete(serverDone);
            })).listen(0, "127.0.0.1").compose(server -> {
                HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + server.actualPort()));
                return vertx.deployVerticle(new VerticleBase() {
                    @Override
                    public Future<?> start() {
                        ContextInternal caller = ContextInternal.current();
                        FlowId original = FlowId.builder().withId("caller").build();
                        if (scenario.localState().equals("value")) {
                            Storage.put("flow_id", original);
                        } else if (scenario.localState().equals("removed")) {
                            Storage.remove("flow_id");
                        }
                        RecordingSink sink = new RecordingSink(caller, model, scenario, original, sinkChanged);
                        BodyCodec<Void> codec = closeSink ? BodyCodec.pipe(sink) : BodyCodec.pipe(sink, false);
                        return client.get(server.actualPort(), "127.0.0.1", "/").timeout(3_000)
                            .as(codec).send().compose(response -> {
                                sink.assertChangedState();
                                assertEquals(version, response.version());
                                assertNull(response.body());
                                assertEquals("no-store", response.getHeader("Cache-Control"));
                                assertEquals("one-two-three", sink.body.toString());
                                assertTrue(sink.writes >= 2);
                                assertEquals(closeSink, sink.ended);
                                Future<Void> released = closeSink ? caller.succeededFuture()
                                    : sink.write(Buffer.buffer("-after-response")).compose(ignored -> sink.end());
                                return released.compose(ignored -> {
                                    sink.assertChangedState();
                                    assertTrue(sink.ended);
                                    assertEquals(closeSink ? "one-two-three" : "one-two-three-after-response", sink.body.toString());
                                    return ContextPropagation.bind(serverDone.future());
                                }).compose(ignored -> vertx.timer(1))
                                    .map(ignored -> {
                                        sink.assertChangedState();
                                        return ignored;
                                    });
                            });
                    }
                }, new DeploymentOptions().setThreadingModel(model));
            }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    // Synchronous collection keeps this fixture always writable; Vert.x owns the actual body piping.
    private static final class RecordingSink implements WriteStream<Buffer> {
        private final ContextInternal caller;
        private final ThreadingModel model;
        private final Scenario scenario;
        private final FlowId original;
        private final Promise<Void> sinkChanged;
        private final FlowId changed = FlowId.builder().withId("changed-in-stream").build();
        private final Buffer body = Buffer.buffer();
        private int writes;
        private boolean ended;

        private RecordingSink(ContextInternal caller, ThreadingModel model, Scenario scenario, FlowId original,
                Promise<Void> sinkChanged) {
            this.caller = caller;
            this.model = model;
            this.scenario = scenario;
            this.original = original;
            this.sinkChanged = sinkChanged;
        }

        @Override
        public Future<Void> write(Buffer data) {
            assertCaller();
            assertFalse(ended);
            if (writes == 0) {
                if (scenario.localState().equals("value")) {
                    assertSame(original, Storage.get("flow_id"));
                } else if (scenario.localState().equals("removed") || scenario.remoteRemoval()) {
                    assertNull(Storage.get("flow_id"));
                    assertTrue(DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                } else {
                    assertEquals("server-response", ((FlowId) Storage.get("flow_id")).getId());
                }
                if (scenario.sinkRemoval()) {
                    Storage.remove("flow_id");
                } else {
                    Storage.put("flow_id", changed);
                }
            }
            assertChangedState();
            if (writes == 0) {
                sinkChanged.complete();
            }
            writes++;
            body.appendBuffer(data);
            return caller.succeededFuture();
        }

        @Override
        public Future<Void> end() {
            assertChangedState();
            assertFalse(ended);
            ended = true;
            return caller.succeededFuture();
        }

        private void assertCaller() {
            assertSame(caller, ContextInternal.current());
            assertEquals(model, caller.threadingModel());
            assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
        }

        private void assertChangedState() {
            assertCaller();
            if (scenario.sinkRemoval()) {
                assertNull(Storage.get("flow_id"));
                assertTrue(DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
            } else {
                assertSame(changed, Storage.get("flow_id"));
                assertFalse(DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
            }
        }

        @Override
        public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        @Override
        public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
            return this;
        }

        @Override
        public boolean writeQueueFull() {
            return false;
        }

        @Override
        public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
            return this;
        }
    }

    private static Stream<Arguments> streamModes() {
        List<Scenario> scenarios = List.of(new Scenario("value", true, false), new Scenario("value", false, true),
            new Scenario("removed", false, false), new Scenario("removed", true, true),
            new Scenario("empty", false, true), new Scenario("empty", true, false));
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of(HttpVersion.HTTP_1_1, HttpVersion.HTTP_2)
                .flatMap(version -> (version == HttpVersion.HTTP_1_1 ? Stream.of(false) : Stream.of(false, true))
                    .flatMap(multiplex -> scenarios.stream().flatMap(scenario -> Stream.of(false, true)
                        .map(closeSink -> Arguments.of(model, version, multiplex, scenario, closeSink))))));
    }
}
