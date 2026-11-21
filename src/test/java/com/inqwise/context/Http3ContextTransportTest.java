package com.inqwise.context;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerConfig;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.core.net.PfxOptions;
import io.vertx.core.net.ServerSSLOptions;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientConfig;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
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
@Timeout(20)
class Http3ContextTransportTest {
    @TempDir
    static Path directory;
    private static PfxOptions certificate;

    @BeforeAll
    static void generateCertificate() throws Exception {
        certificate = HttpTestCertificate.create(directory);
    }

    private record Incoming(HttpServerRequest request, ContextInternal scope, FlowId value, boolean removed) {
        void reply(Vertx vertx, boolean removal, VertxTestContext context) {
            scope.runOnContext(ignored -> vertx.setTimer(1, timer -> context.verify(() -> {
                assertSame(scope, ContextInternal.current());
                assertSame(value, Storage.get("flow_id"));
                assertEquals(removed, DefaultStorage.isRemoved(DefaultStorage.getEntry(scope, "flow_id")));
                if (removal) {
                    Storage.remove("flow_id");
                } else {
                    Storage.put("flow_id", FlowId.builder().withId("server" + request.path()).build());
                }
                request.response().end(request.path());
            })));
        }
    }

    @ParameterizedTest
    @MethodSource("modes")
    void directQuic_overlappingCalls_preserveMetadataAndCallerScopes(ThreadingModel model,
            boolean allowed, boolean removal, Vertx vertx, VertxTestContext testContext) {
        vertx.exceptionHandler(testContext::failNow);
        WebClient client = WebClient.create(vertx,
            new WebClientConfig().setVersions(HttpVersion.HTTP_3).setSsl(true).setVerifyHost(true),
            new ClientSSLOptions().setTrustOptions(new PfxOptions(certificate)));
        HttpServerConfig config = new HttpServerConfig().setVersions(HttpVersion.HTTP_3);
        config.getQuicConfig().setHost("127.0.0.1").setPort(0);
        Map<String, Incoming> incoming = new ConcurrentHashMap<>();
        Promise<Void> received = Promise.promise();
        List<String> completed = new CopyOnWriteArrayList<>();
        vertx.createHttpServer(config, new ServerSSLOptions().setKeyCertOptions(new PfxOptions(certificate)))
            .requestHandler(HttpContextTransport.handler(request -> testContext.verify(() -> {
                assertEquals(HttpVersion.HTTP_3, request.version());
                assertEquals("https", request.scheme());
                String state = request.path().substring(1);
                ContextInternal scope = ContextInternal.current();
                FlowId value = (FlowId) Storage.get("flow_id");
                assertNull(Storage.get("private"));
                if (allowed && state.equals("value")) {
                    assertEquals("caller", value.getId());
                } else {
                    assertNull(value);
                }
                boolean removed = allowed && state.equals("removed");
                assertEquals(removed, DefaultStorage.isRemoved(DefaultStorage.getEntry(scope, "flow_id")));
                assertEquals(allowed && !state.equals("empty"), HttpContextCarrier.hasMetadata(request.headers()));
                Incoming entry = new Incoming(request, scope, value, removed);
                incoming.values().forEach(other -> assertNotSame(other.scope(), scope));
                incoming.put(state, entry);
                if (incoming.size() == 3) {
                    received.complete();
                }
            }))).listen().compose(server -> {
                HttpContextTransport.install(client,
                    URI.create((allowed ? "https" : "http") + "://127.0.0.1:" + server.actualPort()));
                List<Future<String>> deployments = new ArrayList<>();
                for (String state : List.of("value", "removed", "empty")) {
                    deployments.add(vertx.deployVerticle(new VerticleBase() {
                        @Override
                        public Future<?> start() {
                            ContextInternal caller = ContextInternal.current();
                            assertEquals(model, caller.threadingModel());
                            FlowId original = FlowId.builder().withId("caller").build();
                            FlowId local = FlowId.builder().withId(state).build();
                            Storage.put("private", local);
                            if (state.equals("value")) {
                                Storage.put("flow_id", original);
                            } else if (state.equals("removed")) {
                                Storage.remove("flow_id");
                            }
                            return client.get(server.actualPort(), "127.0.0.1", "/" + state)
                                .putHeader(HttpContextCarrier.name("flow_id"), "stale-header")
                                .send().compose(response -> {
                                    assertEquals(HttpVersion.HTTP_3, response.version());
                                    assertEquals("/" + state, response.bodyAsString());
                                    assertEquals("no-store", response.getHeader("Cache-Control"));
                                    String returned = new HttpContextCarrier(response.headers()).getAll("flow_id").getFirst();
                                    FlowIdService codec = new FlowIdService();
                                    assertEquals(removal, codec.isRemoval(returned));
                                    if (!removal) {
                                        assertEquals("server/" + state,
                                            ((FlowId) codec.unmarshalFormString(returned)).getId());
                                    }
                                    assertSame(caller, ContextInternal.current());
                                    FlowId expected = state.equals("value") ? original
                                        : allowed && !removal && state.equals("empty")
                                            ? (FlowId) Storage.get("flow_id") : null;
                                    if (allowed && !removal && state.equals("empty")) {
                                        assertEquals("server/empty", expected.getId());
                                    }
                                    boolean removed = state.equals("removed")
                                        || allowed && removal && state.equals("empty");
                                    assertSame(expected, Storage.get("flow_id"));
                                    assertEquals(removed,
                                        DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                                    assertSame(local, Storage.get("private"));
                                    Promise<Void> timer = Promise.promise();
                                    vertx.setTimer(1, timerId -> testContext.verify(() -> {
                                        assertSame(caller, ContextInternal.current());
                                        assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                                        assertSame(expected, Storage.get("flow_id"));
                                        assertSame(local, Storage.get("private"));
                                        assertEquals(removed,
                                            DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                                        completed.add(state);
                                        timer.complete();
                                    }));
                                    return timer.future();
                                });
                        }
                    }, new DeploymentOptions().setThreadingModel(model)).onFailure(testContext::failNow));
                }
                // Release responses in reverse order while all three callers are pending.
                return received.future().compose(ignored -> {
                    assertTrue(completed.isEmpty());
                    deployments.forEach(deployment -> assertFalse(deployment.isComplete()));
                    incoming.get("empty").reply(vertx, removal, testContext);
                    return deployments.get(2);
                }).compose(ignored -> {
                    incoming.get("removed").reply(vertx, removal, testContext);
                    return deployments.get(1);
                }).compose(ignored -> {
                    incoming.get("value").reply(vertx, removal, testContext);
                    return deployments.getFirst();
                }).map(ignored -> {
                    assertEquals(List.of("empty", "removed", "value"), completed);
                    return ignored;
                });
            }).andThen(ignored -> client.close()).onComplete(testContext.succeedingThenComplete());
    }

    private static Stream<Arguments> modes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of(false, true).flatMap(allowed -> Stream.of(false, true)
                .map(removal -> Arguments.of(model, allowed, removal))));
    }
}
