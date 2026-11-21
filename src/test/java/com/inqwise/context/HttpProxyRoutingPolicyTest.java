package com.inqwise.context;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.net.PfxOptions;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.ProxyType;
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
import org.junit.jupiter.params.provider.MethodSource;

import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class HttpProxyRoutingPolicyTest {
    @TempDir
    static Path directory;
    private static PfxOptions certificate;

    @BeforeAll
    static void generateCertificate() throws Exception {
        certificate = HttpTestCertificate.create(directory);
    }

    @ParameterizedTest
    @MethodSource("proxyModes")
    void client_connectRedirect_keepsMetadataDisabledAcrossRegeneratedOptions(
            String mode, String state, boolean remoteRemoval, Vertx vertx, VertxTestContext testContext) {
        boolean clientDefault = !mode.equals("request");
        vertx.exceptionHandler(testContext::failNow);
        FlowIdService codec = new FlowIdService();
        FlowId original = FlowId.builder().withId("caller").build();
        AtomicInteger tunnels = new AtomicInteger();
        AtomicInteger received = new AtomicInteger();
        AtomicBoolean finishing = new AtomicBoolean();
        var connector = vertx.createNetClient();
        vertx.createHttpServer(new HttpServerOptions().setSsl(true).setKeyCertOptions(new PfxOptions(certificate)))
            .requestHandler(request -> testContext.verify(() -> {
                received.incrementAndGet();
                assertEquals("/final", request.path());
                assertEquals("retained", request.getHeader("application"));
                assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
                new HttpContextCarrier(request.response().headers()).set("flow_id", remoteRemoval
                    ? codec.marshalRemovalToString()
                    : codec.marshalToString(FlowId.builder().withId("final-response").build()));
                request.response().end("delivered");
            })).listen(0, "127.0.0.1").compose(target -> vertx.createHttpServer(
                new HttpServerOptions().setSsl(true).setKeyCertOptions(new PfxOptions(certificate)))
                .requestHandler(request -> testContext.verify(() -> {
                    received.incrementAndGet();
                    assertEquals("/first", request.path());
                    assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
                    request.response().setStatusCode(302)
                        .putHeader(HttpContextCarrier.name("flow_id"), "1:%%%")
                        .putHeader("Location", "https://127.0.0.1:" + target.actualPort() + "/final").end();
                })).listen(0, "127.0.0.1").compose(source -> vertx.createHttpServer()
                    .requestHandler(request -> testContext.verify(() -> {
                        assertEquals(HttpMethod.CONNECT, request.method());
                        assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
                        HostAndPort authority = HostAndPort.parseAuthority(request.uri(), 443);
                        assertEquals("127.0.0.1", authority.host());
                        assertTrue(Set.of(source.actualPort(), target.actualPort()).contains(authority.port()));
                        tunnels.incrementAndGet();
                        connector.connect(authority.port(), authority.host()).compose(upstream -> request.toNetSocket().map(downstream -> {
                            downstream.pipeTo(upstream).onFailure(error -> {
                                if (!finishing.get()) {
                                    testContext.failNow(error);
                                }
                            });
                            upstream.pipeTo(downstream).onFailure(error -> {
                                if (!finishing.get()) {
                                    testContext.failNow(error);
                                }
                            });
                            return downstream;
                        })).onFailure(testContext::failNow);
                    })).listen(0, "127.0.0.1").compose(proxyServer -> {
                        ProxyOptions proxy = new ProxyOptions().setType(ProxyType.HTTP).setHost("127.0.0.1")
                            .setPort(proxyServer.actualPort());
                        WebClientOptions options = new WebClientOptions().setTrustOptions(new PfxOptions(certificate)).setVerifyHost(true);
                        options.setNonProxyHosts(List.of());
                        if (clientDefault) {
                            options.setProxyOptions(proxy);
                        }
                        WebClient client = WebClient.create(vertx, options);
                        HttpContextTransport.install(client, URI.create("https://127.0.0.1:" + source.actualPort()),
                            URI.create("https://127.0.0.1:" + target.actualPort()),
                            URI.create("http://127.0.0.1:" + proxyServer.actualPort()));
                        return inFlow(vertx, "proxy-redirect", ignored -> {
                            ContextInternal caller = ContextInternal.current();
                            if (state.equals("value")) {
                                Storage.put("flow_id", original);
                            } else if (state.equals("removed")) {
                                Storage.remove("flow_id");
                            }
                            var before = DefaultStorage.getEntry(caller, "flow_id");
                            var request = client.getAbs("https://127.0.0.1:" + source.actualPort() + "/first")
                                .putHeader("application", "retained")
                                .putHeader(HttpContextCarrier.name("flow_id"), "stale-managed-header");
                            if (!clientDefault) {
                                request.proxy(proxy);
                            } else if (mode.equals("cleared-default")) {
                                request.proxy(null);
                            }
                            return request.send().compose(response -> {
                                assertEquals("delivered", response.bodyAsString());
                                assertEquals(1, response.followedRedirects().size());
                                assertEquals(2, received.get());
                                assertEquals(clientDefault ? 2 : 1, tunnels.get());
                                assertSame(caller, ContextInternal.current());
                                assertSame(before, DefaultStorage.getEntry(caller, "flow_id"));
                                return vertx.timer(1).map(tick -> {
                                    assertSame(caller, ContextInternal.current());
                                    assertSame(before, DefaultStorage.getEntry(caller, "flow_id"));
                                    return tick;
                                });
                            });
                        }).andThen(ignored -> {
                            finishing.set(true);
                            client.close();
                        });
                    }))).eventually(connector::close).onComplete(testContext.succeedingThenComplete());
    }

    @ParameterizedTest
    @MethodSource("proxyModes")
    void client_connectTunnel_deliversTlsPayloadWithoutMetadata(
            String mode, String state, boolean remoteRemoval, Vertx vertx, VertxTestContext testContext) {
        boolean clientDefault = !mode.equals("request");
        vertx.exceptionHandler(testContext::failNow);
        FlowIdService codec = new FlowIdService();
        FlowId original = FlowId.builder().withId("caller").build();
        AtomicInteger tunnels = new AtomicInteger();
        AtomicInteger received = new AtomicInteger();
        AtomicBoolean finishing = new AtomicBoolean();
        var connector = vertx.createNetClient();
        vertx.createHttpServer(new HttpServerOptions().setSsl(true).setKeyCertOptions(new PfxOptions(certificate)))
            .requestHandler(request -> testContext.verify(() -> {
                received.incrementAndGet();
                assertEquals("https", request.scheme());
                assertEquals("retained", request.getHeader("application"));
                assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
                new HttpContextCarrier(request.response().headers()).set("flow_id", remoteRemoval
                    ? codec.marshalRemovalToString()
                    : codec.marshalToString(FlowId.builder().withId("tunnel-response").build()));
                request.response().end("delivered");
            })).listen(0, "127.0.0.1").compose(target -> vertx.createHttpServer().requestHandler(request -> testContext.verify(() -> {
                assertEquals(HttpMethod.CONNECT, request.method());
                assertEquals("127.0.0.1:" + target.actualPort(), request.uri());
                assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
                tunnels.incrementAndGet();
                connector.connect(target.actualPort(), "127.0.0.1").compose(upstream -> request.toNetSocket().map(downstream -> {
                    downstream.pipeTo(upstream).onFailure(error -> {
                        if (!finishing.get()) {
                            testContext.failNow(error);
                        }
                    });
                    upstream.pipeTo(downstream).onFailure(error -> {
                        if (!finishing.get()) {
                            testContext.failNow(error);
                        }
                    });
                    return downstream;
                })).onFailure(testContext::failNow);
            })).listen(0, "127.0.0.1").compose(proxyServer -> {
                ProxyOptions proxy = new ProxyOptions().setType(ProxyType.HTTP).setHost("127.0.0.1").setPort(proxyServer.actualPort());
                WebClientOptions options = new WebClientOptions().setTrustOptions(new PfxOptions(certificate)).setVerifyHost(true);
                options.setNonProxyHosts(List.of());
                if (clientDefault) {
                    options.setProxyOptions(proxy);
                }
                WebClient client = WebClient.create(vertx, options);
                URI origin = URI.create("https://127.0.0.1:" + target.actualPort());
                HttpContextTransport.install(client, origin, URI.create("http://127.0.0.1:" + proxyServer.actualPort()));
                return inFlow(vertx, "tunnel-caller", ignored -> {
                    ContextInternal caller = ContextInternal.current();
                    if (state.equals("value")) {
                        Storage.put("flow_id", original);
                    } else if (state.equals("removed")) {
                        Storage.remove("flow_id");
                    }
                    var before = DefaultStorage.getEntry(caller, "flow_id");
                    var request = client.getAbs(origin + "/orders").putHeader("application", "retained")
                        .putHeader(HttpContextCarrier.name("flow_id"), "stale-managed-header")
                        .putHeader(HttpContextCarrier.name("private"), "stale-private-header");
                    if (!clientDefault) {
                        request.proxy(proxy);
                    } else if (mode.equals("cleared-default")) {
                        request.proxy(null);
                    }
                    return request.send().compose(response -> {
                        assertEquals("delivered", response.bodyAsString());
                        assertEquals(1, tunnels.get());
                        assertEquals(1, received.get());
                        assertSame(caller, ContextInternal.current());
                        assertSame(before, DefaultStorage.getEntry(caller, "flow_id"));
                        return vertx.timer(1).map(tick -> {
                            assertSame(caller, ContextInternal.current());
                            assertSame(before, DefaultStorage.getEntry(caller, "flow_id"));
                            return tick;
                        });
                    });
                }).andThen(ignored -> {
                    finishing.set(true);
                    client.close();
                });
            })).eventually(connector::close).onComplete(testContext.succeedingThenComplete());
    }

    @ParameterizedTest
    @MethodSource("proxyModes")
    void client_configuredForwardProxy_deliversPayloadWithoutMetadata(
            String mode, String state, boolean remoteRemoval, Vertx vertx, VertxTestContext testContext) {
        boolean clientDefault = !mode.equals("request");
        vertx.exceptionHandler(testContext::failNow);
        FlowIdService codec = new FlowIdService();
        FlowId original = FlowId.builder().withId("caller").build();
        AtomicInteger received = new AtomicInteger();
        // The proxy responds locally; no external upstream or DNS resolution is needed.
        vertx.createHttpServer().requestHandler(request -> testContext.verify(() -> {
            received.incrementAndGet();
            assertEquals("http://trusted.invalid:8080/orders", request.uri());
            assertEquals("trusted.invalid:8080", request.getHeader("Host"));
            assertEquals("retained", request.getHeader("application"));
            assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
            new HttpContextCarrier(request.response().headers()).set("flow_id", remoteRemoval
                ? codec.marshalRemovalToString()
                : codec.marshalToString(FlowId.builder().withId("proxy-response").build()));
            request.response().end("delivered");
        })).listen(0, "127.0.0.1").compose(proxyServer -> {
            ProxyOptions proxy = new ProxyOptions().setType(ProxyType.HTTP).setHost("127.0.0.1").setPort(proxyServer.actualPort());
            WebClientOptions options = new WebClientOptions();
            options.setNonProxyHosts(List.of());
            if (clientDefault) {
                options.setProxyOptions(proxy);
            }
            WebClient client = WebClient.create(vertx, options);
            // Both origins are allowlisted; explicit proxy routing still needs its own trust policy.
            HttpContextTransport.install(client, URI.create("http://trusted.invalid:8080"),
                URI.create("http://127.0.0.1:" + proxyServer.actualPort()));
            return inFlow(vertx, "proxy-caller", ignored -> {
                ContextInternal caller = ContextInternal.current();
                if (state.equals("value")) {
                    Storage.put("flow_id", original);
                } else if (state.equals("removed")) {
                    Storage.remove("flow_id");
                }
                var before = DefaultStorage.getEntry(caller, "flow_id");
                var request = client.getAbs("http://trusted.invalid:8080/orders")
                    .putHeader("application", "retained")
                    .putHeader(HttpContextCarrier.name("flow_id"), "stale-managed-header")
                    .putHeader(HttpContextCarrier.name("private"), "stale-private-header");
                if (!clientDefault) {
                    request.proxy(proxy);
                } else if (mode.equals("cleared-default")) {
                    request.proxy(null);
                }
                return request.send().compose(response -> {
                    assertEquals("delivered", response.bodyAsString());
                    assertEquals(1, received.get());
                    assertSame(caller, ContextInternal.current());
                    assertSame(before, DefaultStorage.getEntry(caller, "flow_id"));
                    return vertx.timer(1).map(tick -> {
                        assertSame(caller, ContextInternal.current());
                        assertSame(before, DefaultStorage.getEntry(caller, "flow_id"));
                        return tick;
                    });
                });
            }).andThen(ignored -> client.close());
        }).onComplete(testContext.succeedingThenComplete());
    }

    private static Stream<Arguments> proxyModes() {
        return Stream.of("request", "default", "cleared-default").flatMap(mode -> Stream.of("value", "removed", "empty")
            .flatMap(state -> Stream.of(false, true).map(removal -> Arguments.of(mode, state, removal))));
    }
}
