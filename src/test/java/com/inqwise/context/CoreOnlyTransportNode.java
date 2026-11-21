package com.inqwise.context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.spi.ContextTransportCarrier;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;

/** Forked entry point deliberately free of JUnit and optional-client references. */
public final class CoreOnlyTransportNode {
    private CoreOnlyTransportNode() {
    }

    /** Runs the core-only application checks. */
    public static void main(String[] args) throws Exception {
        for (String absent : List.of("io.vertx.ext.web.client.WebClient", "io.vertx.kafka.client.producer.KafkaProducer",
                "org.apache.kafka.clients.producer.KafkaProducer", "io.vertx.redis.client.Redis",
                "io.vertx.circuitbreaker.CircuitBreaker", "co.elastic.clients.elasticsearch.ElasticsearchAsyncClient",
                "io.vertx.serviceproxy.ServiceProxyBuilder", "com.hazelcast.core.Hazelcast",
                "org.junit.jupiter.api.Assertions")) {
            try {
                Class.forName(absent);
                throw new AssertionError("Optional dependency leaked into the child: " + absent);
            } catch (ClassNotFoundException expected) {
                // Absence is required; a full test classpath would hide linkage failures.
            }
        }
        check(ContextTransport.defaultConfiguration().includedKeys().equals(Set.of("flow_id")), "Default configuration");
        Vertx vertx = Vertx.vertx();
        try {
            Future<String> receiver = vertx.deployVerticle(new VerticleBase() {
                @Override
                public Future<?> start() {
                    return vertx.eventBus().<String>consumer("core-only", message -> {
                        try {
                            String state = message.body();
                            FlowId incoming = (FlowId) Storage.get("flow_id");
                            if (state.equals("value")) {
                                check(incoming != null && incoming.getId().equals("caller"), "Inbound FlowId");
                            } else {
                                check(incoming == null, "Unexpected inbound FlowId");
                            }
                            MapCarrier carrier = new MapCarrier();
                            ContextTransport.writeTo(carrier);
                            if (state.equals("removed")) {
                                check(new FlowIdService().isRemoval(carrier.getAll("flow_id").getFirst()), "Inbound removal");
                            } else if (state.startsWith("empty")) {
                                check(carrier.values.isEmpty(), "Unexpected inbound marker");
                            }
                            if (state.equals("empty-remove")) {
                                Storage.remove("flow_id");
                            } else {
                                Storage.put("flow_id", FlowId.builder().withId("remote").build());
                            }
                            message.reply("done");
                        } catch (Throwable failure) {
                            message.fail(500, failure.toString());
                        }
                    }).completion();
                }
            });
            Future<Void> checks = receiver.mapEmpty();
            for (ThreadingModel model : List.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER)) {
                for (String state : List.of("value", "removed", "empty-value", "empty-remove")) {
                    checks = checks.compose(ignored -> vertx.deployVerticle(sender(state),
                        new DeploymentOptions().setThreadingModel(model)).mapEmpty());
                }
            }
            checks.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
            System.out.println("CORE_ONLY_TRANSPORT_OK");
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static VerticleBase sender(String state) {
        return new VerticleBase() {
            @Override
            public Future<?> start() {
                ContextInternal caller = ContextInternal.current();
                FlowId original = FlowId.builder().withId("caller").build();
                if (state.equals("value")) {
                    Storage.put("flow_id", original);
                } else if (state.equals("removed")) {
                    Storage.remove("flow_id");
                }
                checkCarrierRoundTrip(state, original);
                return ContextPropagation.bind(vertx.eventBus().<String>request("core-only", state)).map(reply -> {
                    check(ContextInternal.current() == caller, "Caller context changed");
                    check(reply.body().equals("done"), "Reply payload");
                    if (state.equals("value")) {
                        check(Storage.get("flow_id") == original, "Caller value replaced");
                    } else if (state.equals("empty-value")) {
                        check(((FlowId) Storage.get("flow_id")).getId().equals("remote"), "Reply FlowId missing");
                    } else {
                        check(Storage.get("flow_id") == null, "Removal overwritten");
                        MapCarrier carrier = new MapCarrier();
                        ContextTransport.writeTo(carrier);
                        check(new FlowIdService().isRemoval(carrier.getAll("flow_id").getFirst()), "Reply removal missing");
                    }
                    return reply;
                });
            }
        };
    }

    private static void checkCarrierRoundTrip(String state, FlowId original) {
        MapCarrier carrier = new MapCarrier();
        ContextTransport.writeTo(carrier);
        ContextInternal scope = ContextInternal.current().duplicate();
        ContextInternal previous = scope.beginDispatch();
        try {
            ContextTransport.readFrom(carrier);
            if (state.equals("value")) {
                FlowId received = (FlowId) Storage.get("flow_id");
                check(received != original && received.toJson().equals(original.toJson()), "Carrier value round trip");
            } else {
                check(Storage.get("flow_id") == null, "Unexpected carrier value");
                MapCarrier forwarded = new MapCarrier();
                ContextTransport.writeTo(forwarded);
                if (state.equals("removed")) {
                    check(new FlowIdService().isRemoval(forwarded.getAll("flow_id").getFirst()), "Carrier removal round trip");
                } else {
                    check(forwarded.values.isEmpty(), "Unexpected carrier marker");
                }
            }
        } finally {
            scope.endDispatch(previous);
        }
        check(ContextInternal.current() == previous, "Carrier parent context changed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class MapCarrier implements ContextTransportCarrier {
        private final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public List<String> getAll(String key) {
            String value = values.get(key);
            return value == null ? List.of() : List.of(value);
        }

        @Override
        public void set(String key, String value) {
            values.put(key, value);
        }

        @Override
        public void remove(String key) {
            values.remove(key);
        }
    }
}
