package com.inqwise.context;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.inqwise.context.ContextPropagationException.Direction;
import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.spi.ContextChainDataItem;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@ExtendWith(VertxExtension.class)
@Timeout(10)
class EventBusPropagationFailureTest {
    private static final String SECRET = "private-metadata-value";

    private enum MalformedHeader {
        UNKNOWN_VERSION, VALUE_THEN_REMOVAL, REMOVAL_THEN_VALUE, DUPLICATE_VALUE
    }

    @ParameterizedTest
    @MethodSource("inboundFailures")
    void request_malformedInboundMetadata_continuesOrFailsOnlyWhenApplicationRequiresIt(
            MalformedHeader malformed, boolean required, Vertx vertx, VertxTestContext context) {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        vertx.exceptionHandler(errors::add);
        AtomicInteger delivered = new AtomicInteger();
        var consumer = vertx.eventBus().<String>consumer("inbound-failure", message -> context.verify(() -> {
            delivered.incrementAndGet();
            FlowId flow = (FlowId) Storage.get("flow_id");
            if (message.body().equals("bad")) {
                assertNull(flow);
            } else {
                assertEquals("valid", flow.getId());
            }
            if (required && flow == null) {
                message.fail(422, "Required FlowId is missing");
            } else {
                message.reply(message.body());
            }
        }));

        consumer.completion().compose(ignored -> inFlow(vertx, "caller", item -> {
            DeliveryOptions invalid = invalidOptions(malformed);
            return vertx.eventBus().<String>request("inbound-failure", "bad", invalid).transform(result -> {
                assertEquals(required, result.failed());
                if (required) {
                    assertEquals(422, assertInstanceOf(ReplyException.class, result.cause()).failureCode());
                } else {
                    assertEquals("bad", result.result().body());
                }
                assertEquals(1, errors.size());
                assertFailure(errors.getFirst(), Direction.INBOUND, FlowIdService.class.getName(), IllegalArgumentException.class);
                String valid = new FlowIdService().marshalToString(FlowId.builder().withId("valid").build());
                return vertx.eventBus().<String>request("inbound-failure", "next",
                    options().addHeader("flow_id", valid));
            }).map(reply -> {
                assertEquals("next", reply.body());
                assertEquals(2, delivered.get());
                assertEquals(1, errors.size());
                return reply;
            });
        })).eventually(consumer::unregister).onComplete(context.succeedingThenComplete());
    }

    private static Stream<Arguments> inboundFailures() {
        return Stream.of(MalformedHeader.values()).flatMap(malformed -> Stream.of(false, true)
            .map(required -> Arguments.of(malformed, required)));
    }

    private static DeliveryOptions invalidOptions(MalformedHeader malformed) {
        FlowIdService codec = new FlowIdService();
        String value = codec.marshalToString(FlowId.builder().withId(SECRET).build());
        String removal = codec.marshalRemovalToString();
        return switch (malformed) {
            case UNKNOWN_VERSION -> options().addHeader("flow_id", "flow-id:99:json:" + SECRET);
            case VALUE_THEN_REMOVAL -> options().addHeader("flow_id", value).addHeader("FLOW_ID", removal);
            case REMOVAL_THEN_VALUE -> options().addHeader("flow_id", removal).addHeader("FLOW_ID", value);
            case DUPLICATE_VALUE -> options().addHeader("flow_id", value).addHeader("flow_id", value);
        };
    }

    @ParameterizedTest
    @MethodSource("writerFailures")
    void request_failedWriterAndErrorObserver_doNotBlockHealthyProviderOrSubsequentMessages(
            boolean observerThrows, boolean removed, Vertx vertx, VertxTestContext context) {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        AtomicReference<Context> callerContext = new AtomicReference<>();
        AtomicReference<Context> reportedContext = new AtomicReference<>();
        vertx.exceptionHandler(error -> {
            errors.add(error);
            reportedContext.set(Vertx.currentContext());
            if (observerThrows) {
                throw new IllegalStateException(SECRET);
            }
        });
        FlowIdService healthy = new FlowIdService() {
            @Override
            public String key() {
                return "healthy";
            }
        };
        EventBusTransportHandler writer = new EventBusTransportHandler(() -> List.of(new BrokenWriter(), healthy));
        EventBusTransportHandler reader = new EventBusTransportHandler(() -> List.of(healthy));
        vertx.eventBus().addOutboundInterceptor(writer::handleOutbound);
        vertx.eventBus().addInboundInterceptor(reader::handleInbound);
        AtomicInteger delivered = new AtomicInteger();
        var consumer = vertx.eventBus().<String>consumer("outbound-failure", message -> context.verify(() -> {
            delivered.incrementAndGet();
            assertNull(Storage.get("broken"));
            assertNull(message.headers().get("broken"));
            assertEquals(message.body(), ((FlowId) Storage.get("healthy")).getId());
            message.reply(message.body());
        }));

        consumer.completion().compose(ignored -> inFlow(vertx, "first", item -> {
            callerContext.set(Vertx.currentContext());
            FlowId original = FlowId.builder().withId("broken").build();
            Storage.put("broken", original);
            if (removed) {
                Storage.remove("broken");
            }
            Storage.put("healthy", FlowId.builder().withId("first").build());
            DeliveryOptions stale = options().addHeader("broken", "stale-metadata")
                .addHeader("broken", "duplicate-stale-metadata");
            return vertx.eventBus().<String>request("outbound-failure", "first", stale).map(reply -> {
                assertEquals("first", reply.body());
                if (removed) {
                    assertNull(Storage.get("broken"));
                } else {
                    assertSame(original, Storage.get("broken"));
                }
                assertEquals(1, errors.size());
                assertSame(callerContext.get(), reportedContext.get());
                assertFailure(errors.getFirst(), Direction.OUTBOUND, BrokenWriter.class.getName(), IllegalArgumentException.class);
                return reply;
            });
        })).compose(ignored -> inFlow(vertx, "second", item -> {
            Storage.put("healthy", FlowId.builder().withId("second").build());
            return vertx.eventBus().<String>request("outbound-failure", "second", options()).map(reply -> {
                assertEquals("second", reply.body());
                assertEquals(2, delivered.get());
                assertEquals(1, errors.size());
                return reply;
            });
        })).eventually(consumer::unregister).onComplete(context.succeedingThenComplete());
    }

    @ParameterizedTest
    @EnumSource(Direction.class)
    void request_providerDiscoveryFailure_isReportedAndDoesNotStopDelivery(
            Direction direction, Vertx vertx, VertxTestContext context) {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        vertx.exceptionHandler(errors::add);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        EventBusTransportHandler broken = new EventBusTransportHandler(() -> {
            if (failOnce.getAndSet(false)) {
                throw new ServiceConfigurationError(SECRET);
            }
            return List.of();
        });
        if (direction == Direction.INBOUND) {
            vertx.eventBus().addInboundInterceptor(broken::handleInbound);
        } else {
            vertx.eventBus().addOutboundInterceptor(broken::handleOutbound);
        }
        AtomicInteger delivered = new AtomicInteger();
        var consumer = vertx.eventBus().<String>consumer("discovery-failure", message -> {
            delivered.incrementAndGet();
            message.reply(message.body());
        });

        consumer.completion().compose(ignored -> inFlow(vertx, "caller", item -> vertx.eventBus()
            .<String>request("discovery-failure", "first", options()).compose(reply -> {
                assertEquals("first", reply.body());
                assertEquals(1, errors.size());
                assertFailure(errors.getFirst(), direction, null, ServiceConfigurationError.class);
                return vertx.eventBus().<String>request("discovery-failure", "second", options());
            }).map(reply -> {
                assertEquals("second", reply.body());
                assertEquals(2, delivered.get());
                assertEquals(1, errors.size());
                return reply;
            }))).eventually(consumer::unregister).onComplete(context.succeedingThenComplete());
    }

    private static void assertFailure(Throwable error, Direction direction, String provider, Class<?> type) {
        ContextPropagationException failure = assertInstanceOf(ContextPropagationException.class, error);
        assertEquals(direction, failure.getDirection());
        assertEquals(provider, failure.getProviderClassName());
        assertEquals(type.getName(), failure.getFailureType());
        assertNull(failure.getCause());
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        assertFalse(output.toString().contains(SECRET));
    }

    private static DeliveryOptions options() {
        return new DeliveryOptions().setSendTimeout(2_000);
    }

    private static Stream<Arguments> writerFailures() {
        return Stream.of(false, true).flatMap(observerThrows -> Stream.of(false, true)
            .map(removed -> Arguments.of(observerThrows, removed)));
    }

    private static final class BrokenWriter extends FlowIdService {
        @Override
        public String key() {
            return "broken";
        }

        @Override
        public String marshalToString(ContextChainDataItem model) {
            throw new IllegalArgumentException(SECRET, new IllegalStateException(SECRET));
        }

        @Override
        public String marshalRemovalToString() {
            throw new IllegalArgumentException(SECRET, new IllegalStateException(SECRET));
        }
    }
}
