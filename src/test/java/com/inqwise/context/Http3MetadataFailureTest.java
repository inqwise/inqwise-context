package com.inqwise.context;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.inqwise.context.ContextPropagationException.Direction;
import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@ExtendWith(VertxExtension.class)
@Timeout(20)
class Http3MetadataFailureTest {
    @TempDir
    static Path directory;
    private static PfxOptions certificate;

    @BeforeAll
    static void generateCertificate() throws Exception {
        certificate = HttpTestCertificate.create(directory);
    }

    private record Report(Throwable error, ContextInternal scope) {
    }

    @ParameterizedTest
    @MethodSource("failureModes")
    void directQuic_invalidMetadata_deliversPayloadPreservesStateAndRecovers(ThreadingModel model,
            boolean requestFailure, String fault, String state, Vertx vertx, VertxTestContext testContext) {
        List<Report> reports = new CopyOnWriteArrayList<>();
        vertx.exceptionHandler(error -> {
            reports.add(new Report(error, ContextInternal.current()));
            throw new IllegalStateException("Controlled metadata observer failure");
        });
        WebClient client = WebClient.create(vertx,
            new WebClientConfig().setVersions(HttpVersion.HTTP_3).setSsl(true).setVerifyHost(true),
            new ClientSSLOptions().setTrustOptions(new PfxOptions(certificate)));
        FlowId recovered = FlowId.builder().withId("recovered").build();
        String valid = new FlowIdService().marshalToString(recovered);
        AtomicReference<ContextInternal> rejectedScope = new AtomicReference<>();
        HttpServerConfig config = new HttpServerConfig().setVersions(HttpVersion.HTTP_3);
        config.getQuicConfig().setHost("127.0.0.1").setPort(0);
        Handler<HttpServerRequest> application = request -> testContext.verify(() -> {
            assertEquals(HttpVersion.HTTP_3, request.version());
            ContextInternal serverScope = ContextInternal.current();
            assertNull(Storage.get("private"));
            boolean invalid = request.path().equals("/invalid");
            if (invalid) {
                rejectedScope.set(serverScope);
            } else {
                assertNotSame(rejectedScope.get(), serverScope);
            }
            FlowId incoming = (FlowId) Storage.get("flow_id");
            if (requestFailure && !invalid) {
                assertEquals(recovered.toJson(), incoming.toJson());
            } else {
                assertNull(incoming);
                assertFalse(DefaultStorage.isRemoved(DefaultStorage.getEntry(serverScope, "flow_id")));
            }
            request.body().compose(body -> {
                assertSame(serverScope, ContextInternal.current());
                assertEquals("application-payload", body.toString());
                return vertx.timer(1);
            }).onComplete(testContext.succeeding(tick -> testContext.verify(() -> {
                assertSame(serverScope, ContextInternal.current());
                assertSame(incoming, Storage.get("flow_id"));
                if (!requestFailure) {
                    if (invalid) {
                        invalidHeaders(request.response().headers(), fault);
                    } else {
                        new HttpContextCarrier(request.response().headers()).set("flow_id", valid);
                    }
                }
                request.response().putHeader("application", "retained").end(request.path());
            })));
        });
        vertx.createHttpServer(config, new ServerSSLOptions().setKeyCertOptions(new PfxOptions(certificate)))
            .requestHandler(requestFailure ? HttpContextTransport.handler(application) : application)
            .listen().compose(server -> {
                if (!requestFailure) {
                    HttpContextTransport.install(client, URI.create("https://127.0.0.1:" + server.actualPort()));
                }
                return vertx.deployVerticle(new VerticleBase() {
                    @Override
                    public Future<?> start() {
                        ContextInternal caller = ContextInternal.current();
                        FlowId original = FlowId.builder().withId("caller").build();
                        FlowId local = FlowId.builder().withId("private").build();
                        Storage.put("private", local);
                        if (state.equals("value")) {
                            Storage.put("flow_id", original);
                        } else if (state.equals("removed")) {
                            Storage.remove("flow_id");
                        }
                        var invalid = client.post(server.actualPort(), "127.0.0.1", "/invalid");
                        if (requestFailure) {
                            invalidHeaders(invalid.headers(), fault);
                        }
                        int expectedReports = requestFailure || state.equals("empty") ? 1 : 0;
                        return invalid.sendBuffer(Buffer.buffer("application-payload"))
                            .compose(response -> {
                                assertEquals(HttpVersion.HTTP_3, response.version());
                                assertEquals(200, response.statusCode());
                                assertEquals("/invalid", response.bodyAsString());
                                assertEquals("retained", response.getHeader("application"));
                                assertSame(caller, ContextInternal.current());
                                assertSame(state.equals("value") ? original : null, Storage.get("flow_id"));
                                assertEquals(state.equals("removed"),
                                    DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                                assertEquals(expectedReports, reports.size());
                                if (expectedReports == 1) {
                                    Report report = reports.getFirst();
                                    assertSame(requestFailure ? rejectedScope.get() : caller, report.scope());
                                    ContextPropagationException error = assertInstanceOf(ContextPropagationException.class, report.error());
                                    assertEquals(Direction.INBOUND, error.getDirection());
                                    assertEquals(FlowIdService.class.getName(), error.getProviderClassName());
                                    assertFalse(error.getMessage().contains("private-invalid"));
                                    assertNull(error.getCause());
                                }
                                return vertx.timer(1);
                            }).compose(tick -> {
                                assertSame(caller, ContextInternal.current());
                                assertSame(state.equals("value") ? original : null, Storage.get("flow_id"));
                                assertSame(local, Storage.get("private"));
                                assertEquals(state.equals("removed"),
                                    DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                                var next = client.post(server.actualPort(), "127.0.0.1", "/valid");
                                if (requestFailure) {
                                    new HttpContextCarrier(next.headers()).set("flow_id", valid);
                                }
                                return next.sendBuffer(Buffer.buffer("application-payload"));
                            }).compose(response -> {
                                assertEquals(HttpVersion.HTTP_3, response.version());
                                assertEquals("/valid", response.bodyAsString());
                                assertEquals("retained", response.getHeader("application"));
                                assertEquals(expectedReports, reports.size());
                                assertSame(caller, ContextInternal.current());
                                FlowId current = (FlowId) Storage.get("flow_id");
                                if (!requestFailure && state.equals("empty")) {
                                    assertEquals(recovered.toJson(), current.toJson());
                                } else {
                                    assertSame(state.equals("value") ? original : null, current);
                                }
                                assertEquals(state.equals("removed"),
                                    DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                                return vertx.timer(1).map(tick -> {
                                    assertSame(caller, ContextInternal.current());
                                    assertEquals(model, caller.threadingModel());
                                    assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                                    assertSame(current, Storage.get("flow_id"));
                                    assertSame(local, Storage.get("private"));
                                    assertEquals(state.equals("removed"),
                                        DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                                    return tick;
                                });
                            });
                    }
                }, new DeploymentOptions().setThreadingModel(model));
            }).andThen(ignored -> client.close()).onComplete(testContext.succeedingThenComplete());
    }

    private static void invalidHeaders(MultiMap headers, String fault) {
        String name = HttpContextCarrier.name("flow_id");
        switch (fault) {
            case "malformed" -> headers.set(name, "private-invalid-envelope");
            case "oversized" -> headers.set(name, "1:" + "A".repeat(HttpContextCarrier.MAX_VALUE));
            case "duplicate" -> {
                new HttpContextCarrier(headers).set("flow_id", new FlowIdService().marshalRemovalToString());
                headers.add(name, headers.get(name));
            }
            default -> throw new IllegalArgumentException("Unknown test fault");
        }
    }

    private static Stream<Arguments> failureModes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of(false, true).flatMap(request -> Stream.of("malformed", "oversized", "duplicate")
                .flatMap(fault -> Stream.of("value", "removed", "empty")
                    .map(state -> Arguments.of(model, request, fault, state)))));
    }
}
