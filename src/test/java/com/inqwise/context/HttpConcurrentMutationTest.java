package com.inqwise.context;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.internal.ContextInternal;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.WebClientSession;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class HttpConcurrentMutationTest {
    private record Incoming(HttpServerRequest request, ContextInternal scope, FlowId value) {
        void reply(boolean removal) {
            scope.runOnContext(ignored -> {
                assertSame(scope, ContextInternal.current());
                assertSame(value, Storage.get("flow_id"));
                if (removal) {
                    Storage.remove("flow_id");
                } else {
                    Storage.put("flow_id", FlowId.builder().withId("server" + request.path()).build());
                }
                request.response().setStatusCode(removal ? 503 : 200).end(request.path());
            });
        }
    }

    @ParameterizedTest
    @MethodSource("mutationModes")
    void sharedClient_pendingMutationsAndReversedResponses_preserveEachCaller(
            ThreadingModel model, boolean removal, HttpVersion version, boolean multiplex, boolean session,
            Vertx vertx, VertxTestContext testContext) {
        vertx.exceptionHandler(testContext::failNow);
        WebClientOptions options = new WebClientOptions().setProtocolVersion(version).setHttp2ClearTextUpgrade(false);
        options.setHttp2MultiplexImplementation(multiplex);
        WebClient base = WebClient.create(vertx, options);
        WebClient client = session ? WebClientSession.create(base) : base;
        Map<String, Incoming> incoming = new ConcurrentHashMap<>();
        Promise<Void> received = Promise.promise();
        Promise<Void> mutated = Promise.promise();
        AtomicInteger mutationCount = new AtomicInteger();
        List<String> completed = new CopyOnWriteArrayList<>();
        vertx.createHttpServer(new HttpServerOptions().setHttp2MultiplexImplementation(multiplex))
            .requestHandler(HttpContextTransport.handler(request -> testContext.verify(() -> {
            assertEquals(version, request.version());
            if (session) {
                assertEquals("retained", request.getHeader("application"));
            }
            assertNull(request.getHeader(HttpContextCarrier.name("private")));
            String id = request.path().substring(1);
            FlowId value = (FlowId) Storage.get("flow_id");
            assertEquals(id + "-sent", value.getId());
            Incoming entry = new Incoming(request, ContextInternal.current(), value);
            incoming.values().forEach(other -> assertNotSame(other.scope(), entry.scope()));
            incoming.put(id, entry);
            if (incoming.size() == 2) {
                received.complete();
            }
        }))).listen(0, "127.0.0.1").compose(server -> {
            HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + server.actualPort()));
            if (session) {
                WebClientSession shared = (WebClientSession) client;
                shared.addHeader("application", "retained");
                shared.addHeader(HttpContextCarrier.name("flow_id"), "stale-managed-header");
                shared.addHeader(HttpContextCarrier.name("private"), "stale-private-header");
            }
            List<Future<String>> deployments = new ArrayList<>();
            for (String id : List.of("first", "second")) {
                deployments.add(vertx.deployVerticle(new VerticleBase() {
                    @Override
                    public Future<?> start() {
                        ContextInternal caller = ContextInternal.current();
                        assertEquals(model, caller.threadingModel());
                        assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                        FlowId original = FlowId.builder().withId(id + "-sent").build();
                        FlowId current = FlowId.builder().withId(id + "-current").build();
                        Storage.put("flow_id", original);
                        Future<HttpResponse<Buffer>> pending = client.get(server.actualPort(), "127.0.0.1", "/" + id).send();
                        return ContextPropagation.bind(received.future()).compose(ignored -> {
                            assertSame(caller, ContextInternal.current());
                            assertSame(original, Storage.get("flow_id"));
                            assertFalse(pending.isComplete());
                            if (removal) {
                                Storage.remove("flow_id");
                            } else {
                                Storage.put("flow_id", current);
                            }
                            if (mutationCount.incrementAndGet() == 2) {
                                mutated.complete();
                            }
                            return pending.map(response -> {
                                assertSame(caller, ContextInternal.current());
                                assertEquals(version, response.version());
                                assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                                assertEquals("/" + id, response.bodyAsString());
                                assertEquals(id.equals("first") ? 503 : 200, response.statusCode());
                                assertEquals("no-store", response.getHeader("Cache-Control"));
                                String returned = new HttpContextCarrier(response.headers()).getAll("flow_id").getFirst();
                                FlowIdService codec = new FlowIdService();
                                if (id.equals("first")) {
                                    assertTrue(codec.isRemoval(returned));
                                } else {
                                    assertEquals("server/second", ((FlowId) codec.unmarshalFormString(returned)).getId());
                                }
                                if (removal) {
                                    assertNull(Storage.get("flow_id"));
                                    assertTrue(DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                                } else {
                                    assertSame(current, Storage.get("flow_id"));
                                }
                                completed.add(id);
                                return response;
                            }).compose(response -> {
                                Promise<Void> timer = Promise.promise();
                                vertx.setTimer(1, timerId -> testContext.verify(() -> {
                                    assertSame(caller, ContextInternal.current());
                                    if (removal) {
                                        assertNull(Storage.get("flow_id"));
                                        assertTrue(DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                                    } else {
                                        assertSame(current, Storage.get("flow_id"));
                                    }
                                    timer.complete();
                                }));
                                return timer.future();
                            });
                        });
                    }
                }, new DeploymentOptions().setThreadingModel(model)).onFailure(testContext::failNow));
            }
            return mutated.future().compose(ignored -> {
                incoming.get("second").reply(false);
                return deployments.get(1);
            }).compose(ignored -> {
                assertEquals(List.of("second"), completed);
                incoming.get("first").reply(true);
                return Future.join(deployments);
            }).map(ignored -> {
                assertEquals(List.of("second", "first"), completed);
                return ignored;
            });
        }).andThen(ignored -> client.close()).onComplete(testContext.succeedingThenComplete());
    }

    private static Stream<Arguments> mutationModes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of(false, true).flatMap(removal -> Stream.of(HttpVersion.HTTP_1_1, HttpVersion.HTTP_2)
                .flatMap(version -> (version == HttpVersion.HTTP_1_1 ? Stream.of(false) : Stream.of(false, true))
                    .flatMap(multiplex -> Stream.of(false, true)
                        .map(session -> Arguments.of(model, removal, version, multiplex, session))))));
    }
}
