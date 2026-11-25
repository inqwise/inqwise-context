package com.inqwise.context;

import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(5)
class OpenTelemetryCompatibilityTest {
    private static final String TRACE_PARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    @Test
    void traceContext_roundTrip_preservesIdentifiersWithoutChangingCurrentContext() {
        Context before = Context.current();
        Map<String, String> headers = Map.of("traceparent", TRACE_PARENT, "tracestate", "vendor=value");
        W3CTraceContextPropagator propagator = W3CTraceContextPropagator.getInstance();
        Context extracted = propagator.extract(Context.root(), headers, GETTER);
        assertTrue(Span.fromContext(extracted).getSpanContext().isValid());
        assertTrue(Span.fromContext(extracted).getSpanContext().isRemote());
        Map<String, String> outgoing = new HashMap<>();
        propagator.inject(extracted, outgoing, Map::put);
        assertEquals(headers, outgoing);
        assertSame(before, Context.current());
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "\t"})
    void traceState_longOptionalWhitespace_retainsValidMembers(String whitespace) {
        String state = "vendor=value" + whitespace.repeat(16 * 1024) + ",other=retained";
        Context extracted = W3CTraceContextPropagator.getInstance().extract(Context.root(),
            Map.of("traceparent", TRACE_PARENT, "tracestate", state), GETTER);
        assertEquals("value", Span.fromContext(extracted).getSpanContext().getTraceState().get("vendor"));
        assertEquals("retained", Span.fromContext(extracted).getSpanContext().getTraceState().get("other"));
    }

    @ParameterizedTest
    @ValueSource(ints = {8192, 8193})
    void baggage_headerByteLimit_dropsOversizedInput(int length) {
        String value = "a".repeat(length - "key=".length());
        Context extracted = W3CBaggagePropagator.getInstance().extract(Context.root(),
            Map.of("baggage", "key=" + value), GETTER);
        Baggage baggage = Baggage.fromContext(extracted);
        if (length == 8192) {
            assertEquals(value, baggage.getEntryValue("key"));
        } else {
            assertTrue(baggage.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {64, 65, 128})
    void baggage_entryLimit_keepsOnlyTheAllowedPrefix(int count) {
        StringJoiner header = new StringJoiner(",");
        for (int index = 0; index < count; index++) {
            header.add("key" + index + "=value");
        }
        Context extracted = W3CBaggagePropagator.getInstance().extract(Context.root(),
            Map.of("baggage", header.toString()), GETTER);
        Baggage baggage = Baggage.fromContext(extracted);
        assertEquals(64, baggage.size());
        assertEquals("value", baggage.getEntryValue("key63"));
        assertNull(baggage.getEntryValue("key64"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 256})
    void baggage_injection_boundsOutgoingHeadersAndRemainsDecodable(int valueLength) {
        BaggageBuilder builder = Baggage.builder();
        for (int index = 0; index < 128; index++) {
            builder.put("key" + index, "v".repeat(valueLength));
        }
        W3CBaggagePropagator propagator = W3CBaggagePropagator.getInstance();
        Map<String, String> outgoing = new HashMap<>();
        propagator.inject(Context.root().with(builder.build()), outgoing, Map::put);
        assertTrue(outgoing.get("baggage").length() <= 8192);
        Baggage decoded = Baggage.fromContext(propagator.extract(Context.root(), outgoing, GETTER));
        assertTrue(decoded.size() > 0 && decoded.size() <= 64);
        if (valueLength == 1) {
            assertEquals(64, decoded.size());
        } else {
            assertTrue(decoded.size() < 64);
        }
    }
}
