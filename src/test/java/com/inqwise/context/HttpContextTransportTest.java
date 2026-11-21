package com.inqwise.context;

import java.net.URI;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.internal.ContextInternal;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class HttpContextTransportTest {
    @ParameterizedTest
    @MethodSource("roundTrips")
    void clientServer_roundTrip_preservesCallerAndExportsChanges(int status, String action,
            Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        FlowId original = FlowId.builder().withId("caller:\u0394\nquoted-\"id\"").build();
        WebClient client = WebClient.create(vertx);
        vertx.createHttpServer().requestHandler(HttpContextTransport.handler(request -> context.verify(() -> {
            FlowId incoming = (FlowId) Storage.get("flow_id");
            assertNotSame(original, incoming);
            assertEquals(original.toJson(), incoming.toJson());
            ContextInternal scope = ContextInternal.current();
            request.body().onSuccess(body -> context.verify(() -> {
                assertSame(scope, ContextInternal.current());
                assertSame(incoming, Storage.get("flow_id"));
                assertEquals("payload", body.toString());
                vertx.timer(1).onSuccess(ignored -> context.verify(() -> {
                    assertSame(scope, ContextInternal.current());
                    if (action.equals("replace")) {
                        Storage.put("flow_id", FlowId.builder().withId("server").build());
                    } else if (action.equals("remove")) {
                        Storage.remove("flow_id");
                    }
                    request.response().setStatusCode(status).end("done");
                }));
            }));
        }))).listen(0, "127.0.0.1").compose(server -> {
            HttpContextTransport.install(client, origin(server.actualPort()));
            return inFlow(vertx, "caller", ignored -> {
                Storage.put("flow_id", original);
                return client.post(server.actualPort(), "127.0.0.1", "/").sendBuffer(io.vertx.core.buffer.Buffer.buffer("payload"))
                    .map(response -> {
                        assertEquals(status, response.statusCode());
                        assertEquals("done", response.bodyAsString());
                        assertSame(original, Storage.get("flow_id"));
                        assertEquals("no-store", response.getHeader("Cache-Control"));
                        String returned = new HttpContextCarrier(response.headers()).getAll("flow_id").getFirst();
                        if (action.equals("remove")) {
                            assertTrue(new FlowIdService().isRemoval(returned));
                        } else {
                            assertEquals(action.equals("replace") ? "server" : original.getId(),
                                ((FlowId) new FlowIdService().unmarshalFormString(returned)).getId());
                        }
                        return response;
                    });
            });
        }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    private static Stream<Arguments> roundTrips() {
        return Stream.of(200, 503).flatMap(status -> Stream.of("keep", "replace", "remove")
            .map(action -> Arguments.of(status, action)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void response_emptyCaller_importsValueOrRemovalBeforeCallback(boolean removal, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient client = WebClient.create(vertx);
        vertx.createHttpServer().requestHandler(HttpContextTransport.handler(request -> {
            if (removal) {
                Storage.remove("flow_id");
            } else {
                Storage.put("flow_id", FlowId.builder().withId("created-by-server").build());
            }
            request.response().end();
        })).listen(0, "127.0.0.1").compose(server -> {
            HttpContextTransport.install(client, origin(server.actualPort()));
            return inFlow(vertx, "empty", ignored -> client.get(server.actualPort(), "127.0.0.1", "/").send().map(response -> {
                if (removal) {
                    assertNull(Storage.get("flow_id"));
                    MultiMap headers = MultiMap.caseInsensitiveMultiMap();
                    ContextTransport.writeTo(new HttpContextCarrier(headers));
                    assertTrue(new FlowIdService().isRemoval(new HttpContextCarrier(headers).getAll("flow_id").getFirst()));
                } else {
                    assertEquals("created-by-server", ((FlowId) Storage.get("flow_id")).getId());
                }
                return response;
            }));
        }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    @Test
    void sharedClient_overlappingRequests_keepRequestScopesSeparate(Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient client = WebClient.create(vertx);
        List<HttpServerRequest> waiting = new ArrayList<>();
        vertx.createHttpServer().requestHandler(HttpContextTransport.handler(request -> context.verify(() -> {
            assertEquals(request.path().substring(1), ((FlowId) Storage.get("flow_id")).getId());
            waiting.add(request);
            if (waiting.size() == 2) {
                waiting.getLast().response().end(waiting.getLast().path());
                waiting.getFirst().response().end(waiting.getFirst().path());
            }
        }))).listen(0, "127.0.0.1").compose(server -> {
            HttpContextTransport.install(client, origin(server.actualPort()));
            List<Future<Void>> calls = new ArrayList<>();
            for (String id : List.of("first", "second")) {
                calls.add(inFlow(vertx, id, ignored -> {
                    FlowId original = FlowId.builder().withId(id).build();
                    Storage.put("flow_id", original);
                    return client.get(server.actualPort(), "127.0.0.1", "/" + id).send().map(response -> {
                        assertSame(original, Storage.get("flow_id"));
                        String returned = new HttpContextCarrier(response.headers()).getAll("flow_id").getFirst();
                        assertEquals(id, ((FlowId) new FlowIdService().unmarshalFormString(returned)).getId());
                        return response;
                    });
                }));
            }
            return Future.join(calls);
        }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @MethodSource("http2Modes")
    void http2_overlappingStreams_isolateValuesRemovalsAndEmptyStorage(
            boolean upgrade, boolean multiplexImplementation, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClientOptions options = new WebClientOptions().setProtocolVersion(HttpVersion.HTTP_2)
            .setHttp2ClearTextUpgrade(upgrade);
        options.setHttp2MultiplexImplementation(multiplexImplementation);
        WebClient client = WebClient.create(vertx, options);
        List<HttpServerRequest> waiting = new ArrayList<>();
        Map<HttpServerRequest, ContextInternal> scopes = new IdentityHashMap<>();
        vertx.createHttpServer(new HttpServerOptions().setHttp2MultiplexImplementation(multiplexImplementation))
            .requestHandler(HttpContextTransport.handler(request -> context.verify(() -> {
                assertEquals(HttpVersion.HTTP_2, request.version());
                ContextInternal scope = ContextInternal.current();
                scopes.values().forEach(previous -> assertNotSame(previous, scope));
                scopes.put(request, scope);
                request.body().onSuccess(body -> context.verify(() -> {
                    assertSame(scope, ContextInternal.current());
                    String state = request.path().substring(1);
                    assertEquals(state, body.toString());
                    if (state.equals("value")) {
                        assertEquals("caller", ((FlowId) Storage.get("flow_id")).getId());
                    } else {
                        assertNull(Storage.get("flow_id"));
                        MultiMap exported = MultiMap.caseInsensitiveMultiMap();
                        ContextTransport.writeTo(new HttpContextCarrier(exported));
                        assertEquals(state.equals("removed"), HttpContextCarrier.hasMetadata(exported));
                    }
                    waiting.add(request);
                    if (waiting.size() == 3) {
                        for (HttpServerRequest pending : waiting.reversed()) {
                            assertSame(waiting.getFirst().connection(), pending.connection());
                            ContextInternal pendingScope = scopes.get(pending);
                            pendingScope.emit(pending, message -> vertx.timer(1).onSuccess(ignored -> context.verify(() -> {
                                assertSame(pendingScope, ContextInternal.current());
                                if (message.path().equals("/value")) {
                                    Storage.remove("flow_id");
                                } else {
                                    Storage.put("flow_id", FlowId.builder().withId("server" + message.path()).build());
                                }
                                message.response().end(message.path());
                            })));
                        }
                    }
                }));
            }))).listen(0, "127.0.0.1").compose(server -> {
                HttpContextTransport.install(client, origin(server.actualPort()));
                List<Future<Void>> calls = new ArrayList<>();
                for (String state : List.of("value", "removed", "empty")) {
                    calls.add(inFlow(vertx, state, ignored -> {
                        ContextInternal caller = ContextInternal.current();
                        FlowId original = FlowId.builder().withId("caller").build();
                        if (state.equals("value")) {
                            Storage.put("flow_id", original);
                        } else if (state.equals("removed")) {
                            Storage.remove("flow_id");
                        }
                        return client.post(server.actualPort(), "127.0.0.1", "/" + state).sendBuffer(
                            io.vertx.core.buffer.Buffer.buffer(state)).map(response -> {
                                assertEquals(HttpVersion.HTTP_2, response.version());
                                assertEquals("/" + state, response.bodyAsString());
                                assertSame(caller, ContextInternal.current());
                                if (state.equals("value")) {
                                    assertSame(original, Storage.get("flow_id"));
                                    assertTrue(new FlowIdService().isRemoval(
                                        new HttpContextCarrier(response.headers()).getAll("flow_id").getFirst()));
                                } else if (state.equals("removed")) {
                                    assertNull(Storage.get("flow_id"));
                                    MultiMap exported = MultiMap.caseInsensitiveMultiMap();
                                    ContextTransport.writeTo(new HttpContextCarrier(exported));
                                    assertTrue(new FlowIdService().isRemoval(
                                        new HttpContextCarrier(exported).getAll("flow_id").getFirst()));
                                } else {
                                    assertEquals("server/empty", ((FlowId) Storage.get("flow_id")).getId());
                                }
                                return response;
                            });
                    }));
                }
                return Future.join(calls);
            }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    private static Stream<Arguments> http2Modes() {
        return Stream.of(false, true).flatMap(upgrade -> Stream.of(false, true)
            .map(multiplex -> Arguments.of(upgrade, multiplex)));
    }

    @Test
    void deniedOrigin_neitherExportsNorImportsMetadata(Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient client = WebClient.create(vertx);
        vertx.createHttpServer().requestHandler(request -> context.verify(() -> {
            assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
            new HttpContextCarrier(request.response().headers()).set("flow_id",
                new FlowIdService().marshalToString(FlowId.builder().withId("untrusted").build()));
            request.response().end("normal-response");
        })).listen(0, "127.0.0.1").compose(server -> {
            HttpContextTransport.install(client); // An empty origin allowlist denies all propagation.
            return inFlow(vertx, "empty", ignored -> client.get(server.actualPort(), "127.0.0.1", "/")
                .putHeader(HttpContextCarrier.name("flow_id"), "stale-managed-header").send().map(response -> {
                    assertEquals("normal-response", response.bodyAsString());
                    assertNull(Storage.get("flow_id"));
                    return response;
                }));
        }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    @Test
    void redirect_toDeniedOrigin_stripsManagedHeaders(Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient client = WebClient.create(vertx);
        vertx.createHttpServer().requestHandler(request -> context.verify(() -> {
            assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
            request.response().end("redirect-target");
        })).listen(0, "127.0.0.1").compose(target -> vertx.createHttpServer().requestHandler(request -> context.verify(() -> {
            assertTrue(HttpContextCarrier.hasMetadata(request.headers()));
            request.response().setStatusCode(302).putHeader("Location", origin(target.actualPort()) + "/target").end();
        })).listen(0, "127.0.0.1")).compose(source -> {
            HttpContextTransport.install(client, origin(source.actualPort()));
            return inFlow(vertx, "caller", ignored -> {
                FlowId original = FlowId.builder().withId("caller").build();
                Storage.put("flow_id", original);
                return client.get(source.actualPort(), "127.0.0.1", "/").send().map(response -> {
                    assertEquals("redirect-target", response.bodyAsString());
                    assertSame(original, Storage.get("flow_id"));
                    return response;
                });
            });
        }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    @Test
    void malformedResponse_reportsFailureAndStillDeliversBody(Vertx vertx, VertxTestContext context) {
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        vertx.exceptionHandler(failures::add);
        WebClient client = WebClient.create(vertx);
        vertx.createHttpServer().requestHandler(request -> request.response()
            .putHeader(HttpContextCarrier.name("flow_id"), "1:%%%").end("delivered"))
            .listen(0, "127.0.0.1").compose(server -> {
                HttpContextTransport.install(client, origin(server.actualPort()));
                return inFlow(vertx, "empty", ignored -> client.get(server.actualPort(), "127.0.0.1", "/").send().map(response -> {
                    assertEquals("delivered", response.bodyAsString());
                    assertNull(Storage.get("flow_id"));
                    assertEquals(1, failures.size());
                    assertInstanceOf(ContextPropagationException.class, failures.getFirst());
                    return response;
                }));
            }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    @Test
    void oversizedRequest_omitsMetadataWithoutFailingHttp(Vertx vertx, VertxTestContext context) {
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        vertx.exceptionHandler(failures::add);
        WebClient client = WebClient.create(vertx);
        vertx.createHttpServer().requestHandler(HttpContextTransport.handler(request -> {
            assertNull(Storage.get("flow_id"));
            request.response().end("delivered");
        })).listen(0, "127.0.0.1").compose(server -> {
            HttpContextTransport.install(client, origin(server.actualPort()));
            return inFlow(vertx, "large", ignored -> {
                FlowId original = FlowId.builder().withId("x".repeat(3000)).build();
                Storage.put("flow_id", original);
                return client.get(server.actualPort(), "127.0.0.1", "/").send().map(response -> {
                    assertEquals("delivered", response.bodyAsString());
                    assertSame(original, Storage.get("flow_id"));
                    assertEquals(1, failures.size());
                    return response;
                });
            });
        }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void response_preservedCallerState_skipsMalformedEnvelope(boolean removed, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient client = WebClient.create(vertx);
        vertx.createHttpServer().requestHandler(request -> request.response()
            .putHeader(HttpContextCarrier.name("flow_id"), "invalid-envelope").end("delivered"))
            .listen(0, "127.0.0.1").compose(server -> {
                HttpContextTransport.install(client, origin(server.actualPort()));
                return inFlow(vertx, "caller", ignored -> {
                    FlowId original = FlowId.builder().withId("caller").build();
                    Storage.put("flow_id", original);
                    if (removed) {
                        Storage.remove("flow_id");
                    }
                    return client.get(server.actualPort(), "127.0.0.1", "/").send().map(response -> {
                        assertEquals("delivered", response.bodyAsString());
                        if (removed) {
                            assertNull(Storage.get("flow_id"));
                        } else {
                            assertSame(original, Storage.get("flow_id"));
                        }
                        return response;
                    });
                });
            }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    @Test
    void install_invalidOriginsOrRepeatedInstallation_rejects(Vertx vertx) {
        WebClient client = WebClient.create(vertx);
        try {
            for (String invalid : List.of("file:///tmp/", "http://user@localhost", "http://localhost/path",
                    "http://localhost:0", "http://localhost?query", "http://localhost#fragment",
                    "//localhost", "relative", "http:///missing-host", "http://localhost:65536")) {
                assertThrows(IllegalArgumentException.class, () -> HttpContextTransport.install(client, URI.create(invalid)));
            }
            assertSame(client, HttpContextTransport.install(client, origin(8080)));
            assertThrows(IllegalArgumentException.class, () -> HttpContextTransport.install(client, origin(8080)));
        } finally {
            client.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost/", "https://localhost", "https://localhost/",
        "HTTP://LOCALHOST/", "HTTPS://LOCALHOST/"})
    void install_defaultPortsAndRootPaths_acceptsValidOrigins(String origin, Vertx vertx) {
        WebClient client = WebClient.create(vertx);
        try {
            assertSame(client, HttpContextTransport.install(client, URI.create(origin)));
        } finally {
            client.close();
        }
    }

    @Test
    void explicitEmptyConfiguration_disablesClientAndServerPropagation(Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        var empty = ContextTransportConfiguration.builder().build();
        WebClient client = WebClient.create(vertx);
        vertx.createHttpServer().requestHandler(HttpContextTransport.handler(empty, request -> context.verify(() -> {
            assertNull(Storage.get("flow_id"));
            assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
            Storage.put("flow_id", FlowId.builder().withId("server-only").build());
            request.response().end("done");
        }))).listen(0, "127.0.0.1").compose(server -> {
            HttpContextTransport.install(client, empty, origin(server.actualPort()));
            return inFlow(vertx, "caller", ignored -> {
                FlowId original = FlowId.builder().withId("caller-only").build();
                Storage.put("flow_id", original);
                return client.get(server.actualPort(), "127.0.0.1", "/").send().map(response -> {
                    assertSame(original, Storage.get("flow_id"));
                    assertFalse(HttpContextCarrier.hasMetadata(response.headers()));
                    return response;
                });
            });
        }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    @Test
    void connectionFailure_preservesCallerState(Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient client = WebClient.create(vertx);
        vertx.createHttpServer().requestHandler(request -> request.connection().close()).listen(0, "127.0.0.1")
            .compose(server -> {
                HttpContextTransport.install(client, origin(server.actualPort()));
                return inFlow(vertx, "caller", ignored -> {
                    FlowId original = FlowId.builder().withId("caller").build();
                    Storage.put("flow_id", original);
                    return client.get(server.actualPort(), "127.0.0.1", "/").send().transform(result -> {
                        assertTrue(result.failed());
                        assertSame(original, Storage.get("flow_id"));
                        return Future.succeededFuture();
                    });
                });
            }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @MethodSource("bodyFailures")
    void response_bodyDecodingFailure_stillImportsHeadersBeforeFailureCallback(
            String localState, boolean remoteRemoval, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient client = WebClient.create(vertx);
        vertx.createHttpServer().requestHandler(HttpContextTransport.handler(request -> {
            if (remoteRemoval) {
                Storage.remove("flow_id");
            } else {
                Storage.put("flow_id", FlowId.builder().withId("server-response").build());
            }
            request.response().putHeader("Content-Type", "application/json").end("not-json");
        })).listen(0, "127.0.0.1").compose(server -> {
            HttpContextTransport.install(client, origin(server.actualPort()));
            return inFlow(vertx, "caller", ignored -> {
                ContextInternal caller = ContextInternal.current();
                FlowId original = FlowId.builder().withId("caller").build();
                if (!localState.equals("empty")) {
                    Storage.put("flow_id", original);
                }
                if (localState.equals("removed")) {
                    Storage.remove("flow_id");
                }
                return client.get(server.actualPort(), "127.0.0.1", "/").as(BodyCodec.jsonObject()).send()
                    .transform(result -> {
                        assertTrue(result.failed());
                        assertInstanceOf(io.vertx.core.json.DecodeException.class, result.cause());
                        assertSame(caller, ContextInternal.current());
                        if (localState.equals("value")) {
                            assertSame(original, Storage.get("flow_id"));
                        } else if (localState.equals("removed") || remoteRemoval) {
                            assertNull(Storage.get("flow_id"));
                            MultiMap headers = MultiMap.caseInsensitiveMultiMap();
                            ContextTransport.writeTo(new HttpContextCarrier(headers));
                            assertTrue(new FlowIdService().isRemoval(new HttpContextCarrier(headers).getAll("flow_id").getFirst()));
                        } else {
                            assertEquals("server-response", ((FlowId) Storage.get("flow_id")).getId());
                        }
                        return Future.succeededFuture();
                    });
            });
        }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    private static Stream<Arguments> bodyFailures() {
        return Stream.of("empty", "value", "removed").flatMap(local -> Stream.of(false, true)
            .map(remoteRemoval -> Arguments.of(local, remoteRemoval)));
    }

    @Test
    void response_connectionClosedAfterHeaders_retainsImportedMetadata(Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient client = WebClient.create(vertx);
        vertx.createHttpServer().requestHandler(HttpContextTransport.handler(request -> {
            Storage.put("flow_id", FlowId.builder().withId("server-before-close").build());
            request.response().exceptionHandler(ignored -> { });
            request.response().setChunked(true).write("partial-body")
                .onSuccess(ignored -> request.connection().close());
        })).listen(0, "127.0.0.1").compose(server -> {
            HttpContextTransport.install(client, origin(server.actualPort()));
            return inFlow(vertx, "empty", ignored -> {
                ContextInternal caller = ContextInternal.current();
                return client.get(server.actualPort(), "127.0.0.1", "/").send().transform(result -> {
                    assertTrue(result.failed());
                    assertSame(caller, ContextInternal.current());
                    assertEquals("server-before-close", ((FlowId) Storage.get("flow_id")).getId());
                    return Future.succeededFuture();
                });
            });
        }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void server_duplicateRequestMetadata_reportsFailureWithoutBlockingApplication(
            boolean observerThrows, Vertx vertx, VertxTestContext context) {
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        vertx.exceptionHandler(failure -> {
            failures.add(failure);
            if (observerThrows) {
                throw new IllegalStateException("Controlled observer failure");
            }
        });
        WebClient client = WebClient.create(vertx);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        new HttpContextCarrier(headers).set("flow_id", new FlowIdService().marshalRemovalToString());
        String wire = headers.get(HttpContextCarrier.name("flow_id"));
        vertx.createHttpServer().requestHandler(HttpContextTransport.handler(request -> context.verify(() -> {
            assertNull(Storage.get("flow_id"));
            request.response().end("delivered");
        }))).listen(0, "127.0.0.1").compose(server -> {
            var request = client.get(server.actualPort(), "127.0.0.1", "/");
            request.headers().add(HttpContextCarrier.name("flow_id"), wire)
                .add(HttpContextCarrier.name("flow_id").toUpperCase(java.util.Locale.ROOT), wire);
            return request.send().map(response -> {
                assertEquals("delivered", response.bodyAsString());
                assertEquals(1, failures.size());
                assertInstanceOf(ContextPropagationException.class, failures.getFirst());
                return response;
            });
        }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    @Test
    void overriddenAuthority_doesNotBypassOriginPolicy(Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient client = WebClient.create(vertx);
        vertx.createHttpServer().requestHandler(request -> context.verify(() -> {
            assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
            request.response().end("delivered");
        })).listen(0, "127.0.0.1").compose(server -> {
            HttpContextTransport.install(client, origin(1));
            return inFlow(vertx, "caller", ignored -> {
                FlowId original = FlowId.builder().withId("private").build();
                Storage.put("flow_id", original);
                return client.get(server.actualPort(), "127.0.0.1", "/")
                    .putHeader("Host", "127.0.0.1:1").send().map(response -> {
                        assertEquals("delivered", response.bodyAsString());
                        assertSame(original, Storage.get("flow_id"));
                        return response;
                    });
            });
        }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    private static URI origin(int port) {
        return URI.create("http://127.0.0.1:" + port);
    }
}
