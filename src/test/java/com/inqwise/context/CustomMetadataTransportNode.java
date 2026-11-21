package com.inqwise.context;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.CustomMetadataTestProvider.Value;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.internal.ContextInternal;
import io.vertx.ext.web.client.WebClient;

import static com.inqwise.context.CustomMetadataTestProvider.KEY;
import static com.inqwise.context.TransportClasspathTestSupport.check;

/** Forked application registering custom codec and configuration SPI providers. */
public final class CustomMetadataTransportNode {
    private CustomMetadataTransportNode() {
    }

    /** Exercises custom metadata return policies alongside protected FlowId values. */
    public static void main(String[] args) throws Exception {
        TransportClasspathTestSupport.requireAbsent("io.vertx.kafka.client.producer.KafkaProducer", "org.junit.jupiter.api.Assertions");
        check(ContextTransport.defaultConfiguration().includedKeys().equals(Set.of("flow_id", KEY)), "Configuration SPI selection");
        FlowId originalFlow = FlowId.builder().withId("caller-flow").build();
        Value original = new Value("caller:\uD83D\uDE80");
        Value remote = new Value("server:\u0394");
        Vertx vertx = Vertx.vertx();
        WebClient client = WebClient.create(vertx);
        try {
            var server = vertx.createHttpServer().requestHandler(HttpContextTransport.handler(request -> {
                try {
                    ContextInternal scope = ContextInternal.current();
                    FlowId incomingFlow = (FlowId) Storage.get("flow_id");
                    check(incomingFlow != originalFlow && incomingFlow.getId().equals(originalFlow.getId()), "HTTP imported FlowId");
                    String state = request.getParam("state");
                    Value incoming = (Value) Storage.get(KEY);
                    if (state.equals("value")) {
                        check(incoming != original && incoming.equals(original), "HTTP imported custom value");
                    } else {
                        assertState(null, state.equals("removed"));
                    }
                    vertx.timer(1).onComplete(tick -> {
                        try {
                            check(tick.succeeded() && ContextInternal.current() == scope, "Server timer context");
                            check(Storage.get(KEY) == incoming, "Server timer custom value identity");
                            if (request.getParam("action").equals("remove")) {
                                Storage.remove(KEY);
                            } else {
                                Storage.put(KEY, remote);
                            }
                            Storage.remove("flow_id");
                            Storage.put("private", new Value("not-exported"));
                            request.response().end("done");
                        } catch (Throwable failure) {
                            fail(request, failure);
                        }
                    });
                } catch (Throwable failure) {
                    fail(request, failure);
                }
            })).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + server.actualPort()));
            for (String state : List.of("value", "removed", "empty")) {
                for (boolean removal : List.of(false, true)) {
                    vertx.deployVerticle(new VerticleBase() {
                        @Override
                        public Future<?> start() {
                            ContextInternal caller = ContextInternal.current();
                            Storage.put("flow_id", originalFlow);
                            if (state.equals("value")) {
                                Storage.put(KEY, original);
                            } else if (state.equals("removed")) {
                                Storage.remove(KEY);
                            }
                            Value localOnly = new Value("client-private");
                            Storage.put("private", localOnly);
                            return client.get(server.actualPort(), "127.0.0.1", "/").timeout(2_000)
                                .addQueryParam("state", state).addQueryParam("action", removal ? "remove" : "replace")
                                .send().map(response -> {
                                    check(response.statusCode() == 200 && response.bodyAsString().equals("done"), "HTTP payload");
                                    check(ContextInternal.current() == caller, "Client response context");
                                    check(Storage.get("flow_id") == originalFlow, "FlowId policy changed");
                                    check(Storage.get("private") == localOnly, "Private storage changed");
                                    check(new HttpContextCarrier(response.headers()).getAll("private").isEmpty(), "Private header exported");
                                    check(new FlowIdService().isRemoval(new HttpContextCarrier(response.headers())
                                        .getAll("flow_id").getFirst()), "Server FlowId removal missing");
                                    assertState(removal ? null : remote, removal);
                                    if (!removal) {
                                        check(Storage.get(KEY) != remote && Storage.get(KEY) != original, "Custom value was not decoded");
                                    }
                                    Value restored = new Value("client-restored");
                                    Storage.put(KEY, restored);
                                    check(Storage.get(KEY) == restored, "Local replacement identity");
                                    assertState(restored, false);
                                    return response;
                                });
                        }
                    }).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                }
            }
            check(CustomMetadataTestProvider.Configuration.calls.get() == 1, "Configuration SPI evaluated more than once");
            check(CustomMetadataTestProvider.constructed.get() == 1, "Codec SPI instantiated more than once");
            System.out.println("CUSTOM_METADATA_TRANSPORT_OK");
        } finally {
            try {
                client.close();
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void fail(HttpServerRequest request, Throwable failure) {
        request.response().setStatusCode(500).end(failure.toString());
    }

    private static void assertState(Value expected, boolean removal) {
        check(expected == null ? Storage.get(KEY) == null : expected.equals(Storage.get(KEY)), "Custom metadata state");
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        ContextTransport.writeTo(new HttpContextCarrier(headers));
        List<String> values = new HttpContextCarrier(headers).getAll(KEY);
        if (removal) {
            check(values.equals(List.of(CustomMetadataTestProvider.REMOVAL)), "Stored removal marker missing");
        } else if (expected == null) {
            check(values.isEmpty(), "Unexpected removal marker");
        } else {
            check(values.equals(List.of(CustomMetadataTestProvider.VALUE + expected.text())), "Stored replacement not exported");
        }
    }

}
