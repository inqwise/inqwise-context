package com.inqwise.context;

import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.ext.web.client.WebClient;

import static com.inqwise.context.TransportClasspathTestSupport.check;

/** Forked HTTP application with no Kafka client on its runtime classpath. */
public final class HttpOnlyTransportNode {
    private HttpOnlyTransportNode() {
    }

    /** Exercises HTTP request/response propagation with only the HTTP client installed. */
    public static void main(String[] args) throws Exception {
        TransportClasspathTestSupport.requireAbsent("io.vertx.kafka.client.producer.KafkaProducer",
            "org.apache.kafka.clients.producer.KafkaProducer", "org.junit.jupiter.api.Assertions");
        Vertx vertx = Vertx.vertx();
        WebClient client = WebClient.create(vertx);
        try {
            var server = vertx.createHttpServer().requestHandler(HttpContextTransport.handler(request -> {
                try {
                    String state = request.path().substring(1);
                    FlowId incoming = (FlowId) Storage.get("flow_id");
                    if (state.equals("value")) {
                        check(incoming != null && incoming.getId().equals("caller"), "HTTP inbound value");
                    } else {
                        check(incoming == null, "Unexpected HTTP inbound value");
                        if (state.equals("removed")) {
                            MultiMap forwarded = MultiMap.caseInsensitiveMultiMap();
                            ContextTransport.writeTo(new HttpContextCarrier(forwarded));
                            check(new FlowIdService().isRemoval(new HttpContextCarrier(forwarded)
                                .getAll("flow_id").getFirst()), "HTTP imported removal marker");
                        }
                    }
                    if (state.equals("empty-remove")) {
                        Storage.remove("flow_id");
                    } else {
                        Storage.put("flow_id", FlowId.builder().withId("remote").build());
                    }
                    request.response().end("delivered");
                } catch (Throwable failure) {
                    request.response().setStatusCode(500).end(failure.toString());
                }
            })).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + server.actualPort()));
            for (String state : List.of("value", "removed", "empty-value", "empty-remove")) {
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
                        return client.get(server.actualPort(), "127.0.0.1", "/" + state).send().map(response -> {
                            check(response.statusCode() == 200 && response.bodyAsString().equals("delivered"), "HTTP payload");
                            check(ContextInternal.current() == caller, "HTTP caller context");
                            if (state.equals("value")) {
                                check(Storage.get("flow_id") == original, "HTTP caller identity");
                            } else if (state.equals("empty-value")) {
                                check(((FlowId) Storage.get("flow_id")).getId().equals("remote"), "HTTP reply import");
                            } else {
                                check(Storage.get("flow_id") == null, "HTTP removal overwritten");
                                MultiMap exported = MultiMap.caseInsensitiveMultiMap();
                                ContextTransport.writeTo(new HttpContextCarrier(exported));
                                check(new FlowIdService().isRemoval(new HttpContextCarrier(exported)
                                    .getAll("flow_id").getFirst()), "HTTP removal marker");
                            }
                            return response;
                        });
                    }
                }).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            System.out.println("HTTP_ONLY_TRANSPORT_OK");
        } finally {
            client.close();
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
}
