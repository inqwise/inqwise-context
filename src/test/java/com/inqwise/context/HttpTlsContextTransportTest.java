package com.inqwise.context;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.net.ssl.SSLHandshakeException;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.net.PfxOptions;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class HttpTlsContextTransportTest {
    @TempDir
    static Path directory;
    private static PfxOptions generated;

    @BeforeAll
    static void generateCertificate() throws Exception {
        generated = HttpTestCertificate.create(directory);
    }

    private static PfxOptions certificate() {
        return new PfxOptions(generated);
    }

    @ParameterizedTest
    @MethodSource("originModes")
    void originScheme_onlyExactOriginExportsAndImportsMetadata(HttpVersion version, boolean secure,
            boolean allowed, boolean removal, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient client = WebClient.create(vertx, new WebClientOptions().setProtocolVersion(version)
            .setSsl(secure).setUseAlpn(secure).setHttp2ClearTextUpgrade(false)
            .setTrustOptions(certificate()).setVerifyHost(true));
        vertx.createHttpServer(new HttpServerOptions().setSsl(secure).setUseAlpn(secure)
            .setKeyCertOptions(certificate())).requestHandler(HttpContextTransport.handler(request -> context.verify(() -> {
                assertEquals(version, request.version());
                assertEquals(secure ? "https" : "http", request.scheme());
                if (allowed && request.path().equals("/caller")) {
                    assertEquals("caller", ((FlowId) Storage.get("flow_id")).getId());
                } else {
                    assertNull(Storage.get("flow_id"));
                    assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
                }
                if (removal) {
                    Storage.remove("flow_id");
                } else {
                    Storage.put("flow_id", FlowId.builder().withId("server").build());
                }
                request.response().end("delivered");
            }))).listen(0, "127.0.0.1").compose(server -> {
                String scheme = secure == allowed ? "https" : "http";
                HttpContextTransport.install(client, URI.create(scheme + "://127.0.0.1:" + server.actualPort()));
                List<Future<Void>> calls = new ArrayList<>();
                for (boolean existing : List.of(true, false)) {
                    calls.add(inFlow(vertx, existing ? "caller" : "empty", ignored -> {
                        ContextInternal caller = ContextInternal.current();
                        FlowId original = FlowId.builder().withId("caller").build();
                        if (existing) {
                            Storage.put("flow_id", original);
                        }
                        return client.get(server.actualPort(), "127.0.0.1", existing ? "/caller" : "/empty")
                            .putHeader(HttpContextCarrier.name("flow_id"), "stale-managed-header").send().map(response -> {
                                assertEquals(version, response.version());
                                assertEquals("delivered", response.bodyAsString());
                                assertSame(caller, ContextInternal.current());
                                if (existing) {
                                    assertSame(original, Storage.get("flow_id"));
                                } else if (allowed && !removal) {
                                    assertEquals("server", ((FlowId) Storage.get("flow_id")).getId());
                                } else {
                                    assertNull(Storage.get("flow_id"));
                                    MultiMap exported = MultiMap.caseInsensitiveMultiMap();
                                    ContextTransport.writeTo(new HttpContextCarrier(exported));
                                    assertEquals(allowed && removal, HttpContextCarrier.hasMetadata(exported));
                                    if (allowed && removal) {
                                        assertTrue(new FlowIdService().isRemoval(
                                            new HttpContextCarrier(exported).getAll("flow_id").getFirst()));
                                    }
                                }
                                return response;
                            });
                    }));
                }
                return Future.join(calls);
            }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    private static Stream<Arguments> originModes() {
        return Stream.of(HttpVersion.HTTP_1_1, HttpVersion.HTTP_2).flatMap(version -> Stream.of(false, true)
            .flatMap(secure -> Stream.of(false, true).flatMap(allowed -> Stream.of(false, true)
                .map(removal -> Arguments.of(version, secure, allowed, removal)))));
    }

    @ParameterizedTest
    @EnumSource(value = HttpVersion.class, names = {"HTTP_1_1", "HTTP_2"})
    void untrustedCertificate_failsWithoutChangingCallerStorage(HttpVersion version, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient client = WebClient.create(vertx, new WebClientOptions().setSsl(true).setUseAlpn(true)
            .setProtocolVersion(version).setVerifyHost(true));
        vertx.createHttpServer(new HttpServerOptions().setSsl(true).setUseAlpn(true).setKeyCertOptions(certificate()))
            .requestHandler(request -> context.failNow("Untrusted TLS connection reached the application"))
            .listen(0, "127.0.0.1").compose(server -> {
                HttpContextTransport.install(client, URI.create("https://127.0.0.1:" + server.actualPort()));
                return inFlow(vertx, "caller", ignored -> {
                    ContextInternal caller = ContextInternal.current();
                    FlowId original = FlowId.builder().withId("caller").build();
                    Storage.put("flow_id", original);
                    return client.get(server.actualPort(), "127.0.0.1", "/").send().transform(result -> {
                        assertTrue(result.failed());
                        assertInstanceOf(SSLHandshakeException.class, result.cause());
                        assertSame(caller, ContextInternal.current());
                        assertSame(original, Storage.get("flow_id"));
                        return Future.succeededFuture();
                    });
                });
            }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }
}
