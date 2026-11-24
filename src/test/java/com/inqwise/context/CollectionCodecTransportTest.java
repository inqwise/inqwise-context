package com.inqwise.context;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.extension.StringListContext;
import com.inqwise.context.extension.StringMapContext;
import com.inqwise.context.extension.StringSetContext;
import com.inqwise.context.spi.ContextChainDataService;
import com.inqwise.context.spi.ContextTransportCarrier;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.kafka.client.producer.KafkaHeader;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(10)
class CollectionCodecTransportTest {
    private enum Kind {
        MAP(new StringMapContext.Codec(), "string-map:1:json:", "{\"value\":\"private-\u0394\"}", "{}"),
        SET(new StringSetContext.Codec(), "string-set:1:json:", "[\"private-\u0394\"]", "[]"),
        LIST(new StringListContext.Codec(), "string-list:1:json:", "[\"private-\u0394\",\"private-\u0394\"]", "[]");

        final ContextChainDataService codec;
        final String prefix;
        final String populated;
        final String empty;

        Kind(ContextChainDataService codec, String prefix, String populated, String empty) {
            this.codec = codec;
            this.prefix = prefix;
            this.populated = populated;
            this.empty = empty;
        }

        String valid(boolean isEmpty) {
            return prefix + (isEmpty ? empty : populated);
        }

        String invalid(String problem) {
            return switch (problem) {
                case "version" -> prefix.replace(":1:", ":2:") + populated;
                case "syntax" -> prefix + "{\"private-invalid\":";
                case "shape" -> prefix + (this == MAP ? "[]" : "{}");
                case "type" -> prefix + (this == MAP ? "{\"private-invalid\":17}" : "[17]");
                case "null" -> prefix + (this == MAP ? "{\"private-invalid\":null}" : "[null]");
                case "set-duplicates" -> prefix + "[\"private-invalid\",\"private-invalid\"]";
                case "headers" -> valid(false);
                default -> throw new AssertionError("Unknown codec problem");
            };
        }
    }

    @ParameterizedTest
    @MethodSource("validModes")
    void codec_httpAndKafkaCarriers_roundTripUnicodeEmptyValuesAndRemovals(
            Kind kind, boolean empty, boolean kafka, Vertx vertx, VertxTestContext testContext) {
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                ContextInternal context = ContextInternal.current();
                context.exceptionHandler(testContext::failNow);
                var original = kind.codec.unmarshalFormString(kind.valid(empty));
                Storage.put(kind.codec.key(), original);
                ContextTransportCarrier carrier;
                if (kafka) {
                    List<KafkaHeader> headers = new ArrayList<>();
                    carrier = new KafkaContextCarrier(() -> headers);
                } else {
                    carrier = new HttpContextCarrier(MultiMap.caseInsensitiveMultiMap());
                }
                ContextTransportEngine engine = new ContextTransportEngine(() -> List.of(kind.codec));
                engine.write(context, carrier, null);
                Storage.remove(kind.codec.key());
                engine.read(context, carrier, null);
                var decoded = Storage.get(kind.codec.key());
                assertNotSame(original, decoded);
                assertEquals(kind.codec.marshalToString(original), kind.codec.marshalToString(decoded));
                assertFalse(DefaultStorage.isRemoved(DefaultStorage.getEntry(context, kind.codec.key())));
                Storage.remove(kind.codec.key());
                engine.write(context, carrier, null);
                assertTrue(kind.codec.isRemoval(carrier.getAll(kind.codec.key()).getFirst()));
                Storage.put(kind.codec.key(), original);
                engine.read(context, carrier, null);
                assertNull(Storage.get(kind.codec.key()));
                assertTrue(DefaultStorage.isRemoved(DefaultStorage.getEntry(context, kind.codec.key())));
                return Future.succeededFuture();
            }
        }).onComplete(testContext.succeedingThenComplete());
    }

    @ParameterizedTest
    @MethodSource("invalidModes")
    void codec_invalidWireValue_preservesPriorEntryContinuesHealthyImportAndAllowsRecovery(
            Kind kind, String problem, String state, boolean throwingObserver,
            Vertx vertx, VertxTestContext testContext) {
        vertx.deployVerticle(new VerticleBase() {
            @Override
            public Future<?> start() {
                ContextInternal context = ContextInternal.current();
                List<Throwable> errors = new ArrayList<>();
                context.exceptionHandler(failure -> {
                    errors.add(failure);
                    if (throwingObserver) {
                        throw new IllegalStateException("Controlled observer failure");
                    }
                });
                if (state.equals("removed")) {
                    Storage.remove(kind.codec.key());
                } else if (!state.equals("absent")) {
                    Storage.put(kind.codec.key(), kind.codec.unmarshalFormString(kind.valid(state.equals("empty"))));
                }
                var previous = DefaultStorage.getEntry(context, kind.codec.key());
                MultiMap headers = MultiMap.caseInsensitiveMultiMap();
                String name = HttpContextCarrier.name(kind.codec.key());
                HttpContextCarrier carrier = new HttpContextCarrier(headers);
                carrier.set(kind.codec.key(), kind.invalid(problem));
                if (problem.equals("headers")) {
                    headers.add(name, headers.get(name));
                }
                FlowIdService flowCodec = new FlowIdService();
                carrier.set("flow_id", flowCodec.marshalToString(FlowId.builder().withId("healthy").build()));
                ContextTransportEngine engine = new ContextTransportEngine(() -> List.of(kind.codec, flowCodec));
                engine.read(context, carrier, null);
                assertSame(previous, DefaultStorage.getEntry(context, kind.codec.key()));
                FlowId healthy = FlowIdContext.require();
                assertEquals("healthy", healthy.getId());
                assertEquals(1, errors.size());
                var failure = assertInstanceOf(ContextPropagationException.class, errors.getFirst());
                assertEquals(ContextPropagationException.Direction.INBOUND, failure.getDirection());
                assertEquals(kind.codec.getClass().getName(), failure.getProviderClassName());
                assertNull(failure.getCause());
                assertFalse(failure.toString().contains("private-"));
                carrier.set(kind.codec.key(), kind.valid(false));
                engine.read(context, carrier, null);
                assertEquals(kind.valid(false), kind.codec.marshalToString(Storage.get(kind.codec.key())));
                assertSame(healthy, FlowIdContext.require());
                assertEquals(1, errors.size());
                return Future.succeededFuture();
            }
        }).onComplete(testContext.succeedingThenComplete());
    }

    private static Stream<Arguments> validModes() {
        return Stream.of(Kind.values()).flatMap(kind -> Stream.of(false, true)
            .flatMap(empty -> Stream.of(false, true).map(kafka -> Arguments.of(kind, empty, kafka))));
    }

    private static Stream<Arguments> invalidModes() {
        return Stream.of(Kind.values()).flatMap(kind -> {
            List<String> problems = new ArrayList<>(List.of("version", "syntax", "shape", "type", "null", "headers"));
            if (kind == Kind.SET) {
                problems.add("set-duplicates");
            }
            return problems.stream().flatMap(problem -> Stream.of("value", "empty", "removed", "absent")
                .flatMap(state -> Stream.of(false, true).map(observer -> Arguments.of(kind, problem, state, observer))));
        });
    }
}
