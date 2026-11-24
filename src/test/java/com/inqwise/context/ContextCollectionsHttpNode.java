package com.inqwise.context;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.extension.StringListContext;
import com.inqwise.context.extension.StringMapContext;
import com.inqwise.context.extension.StringSetContext;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerConfig;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.core.net.PfxOptions;
import io.vertx.core.net.ServerSSLOptions;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientConfig;
import io.vertx.ext.web.client.WebClientOptions;

import static com.inqwise.context.TransportClasspathTestSupport.check;
import static com.inqwise.context.extension.StringListContext.STEPS;
import static com.inqwise.context.extension.StringMapContext.VALUES;
import static com.inqwise.context.extension.StringSetContext.LABELS;

/** Real loopback HTTP collection propagation with external codec/factory discovery. */
public final class ContextCollectionsHttpNode {
    enum Protocol {
        HTTP1(HttpVersion.HTTP_1_1, false, false),
        HTTP2(HttpVersion.HTTP_2, false, false),
        HTTP2_UPGRADE(HttpVersion.HTTP_2, false, true),
        HTTP2_MULTIPLEX(HttpVersion.HTTP_2, true, false),
        HTTP2_MULTIPLEX_UPGRADE(HttpVersion.HTTP_2, true, true),
        HTTP3(HttpVersion.HTTP_3, false, false);

        final HttpVersion version;
        final boolean multiplex;
        final boolean upgrade;

        Protocol(HttpVersion version, boolean multiplex, boolean upgrade) {
            this.version = version;
            this.multiplex = multiplex;
            this.upgrade = upgrade;
        }
    }

    private static final ContextMap<String, String> PRIVATE = ContextMaps.define("private_map", String.class, String.class);
    private static final ContextTransportConfiguration CONFIGURATION = ContextTransportConfiguration.builder()
        .include(FlowIdContext.slot()).include(VALUES.slot()).include(LABELS.slot()).include(STEPS.slot()).build();

    private record Exchange(String state, FlowId identity, Promise<Void> arrived, Promise<Void> changed) {
        private Exchange(String state, String token) {
            this(state, FlowId.builder().withId("sent-" + token).build(), Promise.promise(), Promise.promise());
        }
    }

    private ContextCollectionsHttpNode() {
    }

    /** Exercises in-flight replacement/removal with each deployed caller threading model. */
    public static void main(String[] args) throws Exception {
        TransportClasspathTestSupport.requireAbsent("io.vertx.kafka.client.producer.KafkaProducer", "org.junit.jupiter.api.Assertions");
        Protocol protocol = Protocol.valueOf(args[0]);
        PfxOptions certificate = protocol == Protocol.HTTP3 ? HttpTestCertificate.create(Path.of(args[1])) : null;
        Vertx vertx = Vertx.vertx();
        WebClient client;
        HttpServer listener;
        if (protocol == Protocol.HTTP3) {
            client = WebClient.create(vertx, new WebClientConfig().setVersions(HttpVersion.HTTP_3).setSsl(true).setVerifyHost(true),
                new ClientSSLOptions().setTrustOptions(new PfxOptions(certificate)));
            HttpServerConfig config = new HttpServerConfig().setVersions(HttpVersion.HTTP_3);
            config.getQuicConfig().setHost("127.0.0.1").setPort(0);
            listener = vertx.createHttpServer(config, new ServerSSLOptions().setKeyCertOptions(new PfxOptions(certificate)));
        } else {
            WebClientOptions options = new WebClientOptions().setProtocolVersion(protocol.version)
                .setHttp2ClearTextUpgrade(protocol.upgrade);
            options.setHttp2MultiplexImplementation(protocol.multiplex);
            client = WebClient.create(vertx, options);
            listener = vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0)
                .setHttp2MultiplexImplementation(protocol.multiplex));
        }
        Map<String, Exchange> exchanges = new ConcurrentHashMap<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        vertx.exceptionHandler(failure -> unexpected.compareAndSet(null, failure));
        try {
            var server = listener.requestHandler(HttpContextTransport.handler(CONFIGURATION,
                request -> handle(vertx, request, exchanges.get(request.path().substring(1)), protocol.version)))
                .listen().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            HttpContextTransport.install(client, CONFIGURATION,
                URI.create((protocol == Protocol.HTTP3 ? "https" : "http") + "://127.0.0.1:" + server.actualPort()));
            for (ThreadingModel model : List.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)) {
                for (String state : List.of("value", "empty", "removed", "absent")) {
                    String token = model.name() + "-" + state;
                    Exchange exchange = new Exchange(state, token);
                    exchanges.put(token, exchange);
                    vertx.deployVerticle(new VerticleBase() {
                        @Override
                        public Future<?> start() {
                            ContextInternal caller = ContextInternal.current();
                            FlowIdContext.put(exchange.identity());
                            prepareCaller(state);
                            PRIVATE.replace(Map.of("caller", token));
                            ContextMapItem<String, String> privateItem = PRIVATE.slot().require();
                            var pending = client.get(server.actualPort(), "127.0.0.1", "/" + token)
                                .putHeader("application", token).timeout(3_000).send();
                            return ContextPropagation.bind(exchange.arrived().future()).compose(ignored -> {
                                check(ContextInternal.current() == caller, "Caller barrier context");
                                changePendingCaller(state);
                                ContextMapItem<String, String> pendingMap = VALUES.slot().require();
                                ContextSetItem<String> pendingSet = LABELS.slot().require();
                                ContextListItem<String> pendingList = STEPS.slot().require();
                                FlowId retained = FlowIdContext.get();
                                exchange.changed().complete();
                                return pending.compose(response -> {
                                    check(ContextInternal.current() == caller, "Caller response context");
                                    check(response.version() == protocol.version, "HTTP response version");
                                    check(response.statusCode() == 200 && token.equals(response.bodyAsString()),
                                        "HTTP collection payload: " + response.bodyAsString());
                                    check(token.equals(response.getHeader("application")), "Application response header");
                                    check("no-store".equals(response.getHeader("Cache-Control")), "Response cache policy");
                                    check(FlowIdContext.get() == retained, "HTTP return changed current caller FlowId");
                                    if (retained == null) {
                                        expectFailure(FlowIdContext::getOrCreate, "Returned FlowId revived caller removal");
                                    }
                                    check(PRIVATE.slot().require() == privateItem, "HTTP return changed private caller storage");
                                    check(new HttpContextCarrier(response.headers()).getAll(PRIVATE.slot().key()).isEmpty(),
                                        "Private response metadata was exported");
                                    assertReturned(state);
                                    check(VALUES.slot().get() != pendingMap && LABELS.slot().get() != pendingSet
                                        && STEPS.slot().get() != pendingList, "Returned collections did not replace pending snapshots");
                                    check("pending".equals(pendingMap.snapshot().get("caller")), "Old pending map mutated");
                                    check(pendingSet.snapshot().contains("caller-pending"), "Old pending set mutated");
                                    check(pendingList.snapshot().contains("caller-pending"), "Old pending list mutated");
                                    ContextMapItem<String, String> returnedMap = VALUES.slot().get();
                                    ContextSetItem<String> returnedSet = LABELS.slot().get();
                                    ContextListItem<String> returnedList = STEPS.slot().get();
                                    return vertx.timer(1).map(tick -> {
                                        check(ContextInternal.current() == caller && caller.threadingModel() == model,
                                            "Caller timer context/model");
                                        check(Thread.currentThread().isVirtual() == (model == ThreadingModel.VIRTUAL_THREAD),
                                            "Caller timer thread model");
                                        check(FlowIdContext.get() == retained, "Caller timer FlowId identity");
                                        check(VALUES.slot().get() == returnedMap && LABELS.slot().get() == returnedSet
                                            && STEPS.slot().get() == returnedList, "Caller timer collection identity");
                                        return tick;
                                    });
                                });
                            });
                        }
                    }, new DeploymentOptions().setThreadingModel(model))
                        .toCompletionStage().toCompletableFuture().get(6, TimeUnit.SECONDS);
                }
            }
            check(unexpected.get() == null, "Unexpected metadata or asynchronous failure");
            check(StringMapContext.Factory.created.get() == 9 && StringSetContext.Factory.created.get() == 9
                && StringListContext.Factory.created.get() == 9, "Import or absent metadata invoked factories");
            System.out.println("CONTEXT_COLLECTIONS_HTTP_OK");
        } finally {
            try {
                client.close();
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void prepareCaller(String state) {
        if (state.equals("absent")) {
            assertNoValues(false);
            return;
        }
        check(VALUES.getOrCreate().equals(Map.of("factory", "configured")), "Map initialization");
        check(LABELS.getOrCreate().equals(Set.of("configured")), "Set initialization");
        check(STEPS.getOrCreate().equals(List.of("configured")), "List initialization");
        VALUES.put("outgoing", "value");
        LABELS.add("outgoing");
        STEPS.appendAll(List.of("outgoing", "outgoing"));
        if (state.equals("empty")) {
            VALUES.clear();
            LABELS.clear();
            STEPS.clear();
        } else if (state.equals("removed")) {
            VALUES.remove();
            LABELS.remove();
            STEPS.remove();
            assertNoValues(true);
        }
    }

    private static void changePendingCaller(String state) {
        if (state.equals("removed") || state.equals("absent")) {
            VALUES.replace(Map.of("caller", "pending"));
            LABELS.replace(Set.of("caller-pending"));
            STEPS.replace(List.of("caller-pending", "caller-pending"));
        } else {
            VALUES.put("caller", "pending");
            LABELS.add("caller-pending");
            STEPS.append("caller-pending");
        }
        if (state.equals("value")) {
            FlowIdContext.put(FlowId.builder().withId("caller-current").build());
        } else if (state.equals("empty")) {
            FlowIdContext.remove();
        }
    }

    private static void handle(Vertx vertx, HttpServerRequest request, Exchange exchange, HttpVersion version) {
        try {
            check(exchange != null, "Unknown exchange");
            check(request.version() == version, "HTTP request version");
            check(request.scheme().equals(version == HttpVersion.HTTP_3 ? "https" : "http"), "HTTP request scheme");
            String token = request.path().substring(1);
            check(token.equals(request.getHeader("application")), "Application request header");
            ContextInternal receiver = ContextInternal.current();
            FlowId received = FlowIdContext.require();
            check(received != exchange.identity() && received.getId().equals(exchange.identity().getId()), "HTTP FlowId decode");
            check(PRIVATE.slot().get() == null, "Private metadata leaked between requests or from caller");
            PRIVATE.replace(Map.of("server", token));
            assertSent(exchange.state());
            ContextMapItem<String, String> incomingMap = VALUES.slot().get();
            ContextSetItem<String> incomingSet = LABELS.slot().get();
            ContextListItem<String> incomingList = STEPS.slot().get();
            exchange.arrived().complete();
            ContextPropagation.bind(exchange.changed().future()).compose(ignored -> vertx.timer(1)).compose(tick -> {
                check(ContextInternal.current() == receiver, "Server timer context");
                check(FlowIdContext.require() == received, "Server timer FlowId identity");
                check(VALUES.slot().get() == incomingMap && LABELS.slot().get() == incomingSet
                    && STEPS.slot().get() == incomingList, "Caller mutation changed server snapshots");
                changeServer(exchange.state());
                FlowIdContext.put(FlowId.builder().withId("server-return").build());
                return request.response().putHeader("application", token).end(token);
            }).onFailure(failure -> request.response().setStatusCode(500).end(failure.toString()));
        } catch (Throwable failure) {
            request.response().setStatusCode(500).end(failure.toString());
        }
    }

    private static void assertSent(String state) {
        switch (state) {
            case "value" -> {
                check(VALUES.snapshot().equals(Map.of("factory", "configured", "outgoing", "value")), "Sent map snapshot");
                check(LABELS.snapshot().equals(Set.of("configured", "outgoing")), "Sent set snapshot");
                check(STEPS.snapshot().equals(List.of("configured", "outgoing", "outgoing")), "Sent list order/duplicates");
            }
            case "empty" -> check(VALUES.snapshot().isEmpty() && LABELS.snapshot().isEmpty() && STEPS.snapshot().isEmpty(),
                "Empty values were not decoded as present containers");
            case "removed" -> assertNoValues(true);
            case "absent" -> assertNoValues(false);
            default -> throw new AssertionError("Unknown state");
        }
    }

    private static void changeServer(String state) {
        if (state.equals("value")) {
            VALUES.remove("outgoing");
            VALUES.put("server", "changed");
            LABELS.remove("outgoing");
            LABELS.add("server");
            STEPS.removeAt(1);
            STEPS.appendAll(List.of("server", "server"));
        } else if (state.equals("empty")) {
            VALUES.remove();
            LABELS.remove();
            STEPS.remove();
        } else {
            VALUES.replace(Map.of("server", "restored"));
            LABELS.replace(Set.of("server-restored"));
            STEPS.replace(List.of("server-restored", "server-restored"));
        }
    }

    private static void assertReturned(String state) {
        if (state.equals("empty")) {
            assertNoValues(true);
        } else if (state.equals("value")) {
            check(VALUES.snapshot().equals(Map.of("factory", "configured", "server", "changed")), "Returned map replacement");
            check(LABELS.snapshot().equals(Set.of("configured", "server")), "Returned set replacement");
            check(STEPS.snapshot().equals(List.of("configured", "outgoing", "server", "server")), "Returned list order/duplicates");
        } else {
            check(VALUES.snapshot().equals(Map.of("server", "restored")), "Returned restored map");
            check(LABELS.snapshot().equals(Set.of("server-restored")), "Returned restored set");
            check(STEPS.snapshot().equals(List.of("server-restored", "server-restored")), "Returned restored list");
        }
    }

    private static void assertNoValues(boolean removed) {
        check(VALUES.slot().get() == null && LABELS.slot().get() == null && STEPS.slot().get() == null, "Unexpected stored collection");
        HttpContextCarrier carrier = new HttpContextCarrier(MultiMap.caseInsensitiveMultiMap());
        ContextTransport.writeTo(carrier, CONFIGURATION);
        check(carrier.getAll(VALUES.slot().key()).size() == (removed ? 1 : 0), "Map removal/absence distinction");
        check(carrier.getAll(LABELS.slot().key()).size() == (removed ? 1 : 0), "Set removal/absence distinction");
        check(carrier.getAll(STEPS.slot().key()).size() == (removed ? 1 : 0), "List removal/absence distinction");
        if (removed) {
            check(new StringMapContext.Codec().isRemoval(carrier.getAll(VALUES.slot().key()).getFirst()), "Map marker encoding");
            check(new StringSetContext.Codec().isRemoval(carrier.getAll(LABELS.slot().key()).getFirst()), "Set marker encoding");
            check(new StringListContext.Codec().isRemoval(carrier.getAll(STEPS.slot().key()).getFirst()), "List marker encoding");
            expectFailure(() -> VALUES.getOrCreate(() -> { throw new AssertionError("Map factory revival"); }), "Map revived");
            expectFailure(() -> LABELS.getOrCreate(() -> { throw new AssertionError("Set factory revival"); }), "Set revived");
            expectFailure(() -> STEPS.getOrCreate(() -> { throw new AssertionError("List factory revival"); }), "List revived");
        }
    }

    private static void expectFailure(Runnable operation, String message) {
        try {
            operation.run();
            throw new AssertionError(message);
        } catch (IllegalStateException expected) {
            // A removal marker requires explicit reintroduction, never a factory call.
        }
    }
}
