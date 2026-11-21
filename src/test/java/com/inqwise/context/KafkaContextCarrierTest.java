package com.inqwise.context;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.data.FlowIdService.CodecProfile;
import io.vertx.core.buffer.Buffer;
import io.vertx.kafka.client.producer.KafkaHeader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaContextCarrierTest {
    @ParameterizedTest
    @MethodSource("invalidKeys")
    void name_invalidKey_rejectsBeforeAccessingHeaders(String key) {
        KafkaContextCarrier carrier = new KafkaContextCarrier(() -> {
            throw new AssertionError("Invalid keys must not access headers");
        });
        assertThrows(IllegalArgumentException.class, () -> KafkaContextCarrier.name(key));
        assertThrows(IllegalArgumentException.class, () -> carrier.set(key, "valid"));
        assertThrows(IllegalArgumentException.class, () -> carrier.remove(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0394", "\uD83D\uDE80"})
    void name_maximumUnicodeKey_preservesSpellingAndUsesUtf16Length(String unit) {
        String key = unit.repeat(128 / unit.length());
        List<KafkaHeader> headers = new ArrayList<>();
        KafkaContextCarrier carrier = new KafkaContextCarrier(() -> headers);
        carrier.set(key, "retained");
        assertEquals(128, key.length());
        assertEquals(KafkaContextCarrier.PREFIX + key, headers.getFirst().key());
        assertEquals(List.of("retained"), carrier.getAll(key));
        carrier.remove(key);
        assertTrue(headers.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0394", "\u20AC", "\uD83D\uDE80"})
    void value_exactUtf8ByteLimit_roundTripsAndRejectsOneMoreByteWithoutMutation(String unit) {
        int limit = KafkaContextCarrier.MAX_VALUE - 2;
        int width = unit.getBytes(StandardCharsets.UTF_8).length;
        String maximum = unit.repeat(limit / width) + "x".repeat(limit % width);
        List<KafkaHeader> headers = new ArrayList<>();
        KafkaHeader application = KafkaHeader.header("application", "retained");
        headers.add(application);
        KafkaContextCarrier carrier = new KafkaContextCarrier(() -> headers);
        carrier.set("flow_id", maximum);
        KafkaHeader original = headers.getLast();
        Buffer originalBuffer = original.value();
        assertEquals(KafkaContextCarrier.MAX_VALUE, originalBuffer.length());
        assertEquals(List.of(maximum), carrier.getAll("flow_id"));

        assertThrows(IllegalArgumentException.class, () -> carrier.set("flow_id", maximum + "x"));
        assertSame(application, headers.getFirst());
        assertSame(original, headers.getLast());
        assertSame(originalBuffer, headers.getLast().value());
        assertEquals(List.of(maximum), carrier.getAll("flow_id"));

        KafkaHeader oversized = KafkaHeader.header(original.key(), originalBuffer.copy().appendByte((byte) 'x'));
        headers.set(1, oversized);
        assertThrows(IllegalArgumentException.class, () -> carrier.getAll("flow_id"));
        assertSame(oversized, headers.getLast());
        assertSame(application, headers.getFirst());
    }

    @Test
    void aggregate_exactUtf8KeyAndValueLimit_rejectsGrowthWithoutReplacingExistingHeaders() {
        String first = "\u0394".repeat(128);
        String second = "\u03BB".repeat(128);
        List<KafkaHeader> headers = new ArrayList<>();
        KafkaContextCarrier carrier = new KafkaContextCarrier(() -> headers);
        carrier.set(first, "x".repeat(KafkaContextCarrier.MAX_VALUE - 2));
        int remaining = KafkaContextCarrier.MAX_TOTAL - headers.getFirst().key().getBytes(StandardCharsets.UTF_8).length
            - headers.getFirst().value().length() - KafkaContextCarrier.name(second).getBytes(StandardCharsets.UTF_8).length - 2;
        String maximum = "\u20AC".repeat(remaining / 3) + "x".repeat(remaining % 3);
        carrier.set(second, maximum);
        carrier.set(second, maximum);
        KafkaHeader original = headers.getLast();
        assertEquals(KafkaContextCarrier.MAX_TOTAL, headers.stream().mapToInt(header ->
            header.key().getBytes(StandardCharsets.UTF_8).length + header.value().length()).sum());
        assertEquals(List.of(maximum), carrier.getAll(second));

        assertThrows(IllegalArgumentException.class, () -> carrier.set(second, maximum + "x"));
        assertSame(original, headers.getLast());
        assertThrows(IllegalArgumentException.class, () -> carrier.set("third", ""));
        assertTrue(carrier.getAll("third").isEmpty());
        assertEquals(2, headers.size());
        headers.add(KafkaHeader.header(KafkaContextCarrier.name("unknown"), "1:"));
        assertThrows(IllegalArgumentException.class, () -> carrier.getAll(first));
    }

    private static Stream<String> invalidKeys() {
        return Stream.of("x".repeat(129), "\u0394".repeat(129), "\uD83D\uDE80".repeat(65),
            "\uD800", "\uDC00", "before\uD800after", "\uDC00\uD800");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\uD800", "\uDC00", "before\uD800after", "\uDC00\uD800"})
    void set_malformedText_rejectsWithoutReplacingHeaders(String value) {
        List<KafkaHeader> headers = new ArrayList<>();
        headers.add(KafkaHeader.header("application", "retained"));
        KafkaContextCarrier carrier = new KafkaContextCarrier(() -> headers);
        carrier.set("flow_id", "original");

        assertThrows(IllegalArgumentException.class, () -> carrier.set("flow_id", value));
        assertEquals(List.of("original"), carrier.getAll("flow_id"));
        assertEquals("retained", headers.getFirst().value().toString());
        assertEquals(2, headers.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\uD800\uDC00", "before\uD83D\uDE80after"})
    void set_validUnicode_roundTripsWithoutReplacement(String value) {
        List<KafkaHeader> headers = new ArrayList<>();
        KafkaContextCarrier carrier = new KafkaContextCarrier(() -> headers);
        carrier.set("flow_id", value);
        assertEquals(List.of(value), carrier.getAll("flow_id"));
    }

    @ParameterizedTest
    @EnumSource(CodecProfile.class)
    void carrier_allProfiles_preserveUnicodeAndRemoval(CodecProfile profile) {
        List<KafkaHeader> headers = new ArrayList<>();
        KafkaContextCarrier carrier = new KafkaContextCarrier(() -> headers);
        FlowIdService codec = new FlowIdService(profile);
        FlowId original = FlowId.builder().withId("unicode:\u0394\nquoted-\"id\"").build();
        carrier.set("flow_id", codec.marshalToString(original));
        assertEquals(original.toJson(), ((FlowId) codec.unmarshalFormString(carrier.getAll("flow_id").getFirst())).toJson());
        carrier.set("flow_id", codec.marshalRemovalToString());
        assertEquals(1, headers.size());
        assertTrue(codec.isRemoval(carrier.getAll("flow_id").getFirst()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void carrier_invalidValues_rejectNullVersionAndUtf8(int mode) {
        Buffer value = switch (mode) {
            case 0 -> null;
            case 1 -> Buffer.buffer("2:value");
            case 2 -> Buffer.buffer(new byte[] {'1', ':', (byte) 0xff});
            case 4 -> Buffer.buffer();
            case 5 -> Buffer.buffer("1;");
            default -> Buffer.buffer("1");
        };
        List<KafkaHeader> headers = new ArrayList<>();
        headers.add(KafkaHeader.header(KafkaContextCarrier.name("flow_id"), value));
        KafkaContextCarrier carrier = new KafkaContextCarrier(() -> headers);
        assertThrows(IllegalArgumentException.class, () -> carrier.getAll("flow_id"));
    }

    @Test
    void aggregate_nullUnknownMetadataValue_countsItsKeyWithoutMutatingIt() {
        List<KafkaHeader> headers = new ArrayList<>();
        KafkaHeader unknown = KafkaHeader.header(KafkaContextCarrier.name("unknown"), (Buffer) null);
        headers.add(unknown);
        KafkaContextCarrier carrier = new KafkaContextCarrier(() -> headers);
        carrier.set("flow_id", "retained");
        assertEquals(List.of("retained"), carrier.getAll("flow_id"));
        assertSame(unknown, headers.getFirst());
        assertNull(unknown.value());
        carrier.remove("flow_id");
        assertEquals(List.of(unknown), headers);
    }

    @Test
    void carrier_duplicateAndCaseSensitiveKeys_areNotCollapsed() {
        List<KafkaHeader> headers = new ArrayList<>();
        KafkaContextCarrier carrier = new KafkaContextCarrier(() -> headers);
        carrier.set("flow_id", "first");
        carrier.set("FLOW_ID", "different-key");
        headers.add(KafkaHeader.header(KafkaContextCarrier.name("flow_id"), "1:second"));
        assertEquals(List.of("first", "second"), carrier.getAll("flow_id"));
        carrier.remove("flow_id");
        assertTrue(carrier.getAll("flow_id").isEmpty());
        assertEquals(List.of("different-key"), carrier.getAll("FLOW_ID"));
    }

    @Test
    void carrier_limits_boundEncodedAndAggregateBytes() {
        List<KafkaHeader> headers = new ArrayList<>();
        KafkaContextCarrier carrier = new KafkaContextCarrier(() -> headers);
        String maximum = "x".repeat(KafkaContextCarrier.MAX_VALUE - 2);
        carrier.set("flow_id", maximum);
        assertEquals(KafkaContextCarrier.MAX_VALUE, headers.getFirst().value().length());
        carrier.set("flow_id", maximum);
        assertEquals(List.of(maximum), carrier.getAll("flow_id"));
        assertThrows(IllegalArgumentException.class, () -> carrier.set("flow_id", maximum + "x"));
        carrier.remove("flow_id");
        carrier.set("first", "x".repeat(100_000));
        carrier.set("second", "x".repeat(100_000));
        assertThrows(IllegalArgumentException.class, () -> carrier.set("third", "x".repeat(100_000)));
        assertTrue(carrier.getAll("third").isEmpty());
        headers.add(KafkaHeader.header(KafkaContextCarrier.name("other"), Buffer.buffer(new byte[100_000])));
        assertThrows(IllegalArgumentException.class, () -> carrier.getAll("first"));
    }
}
