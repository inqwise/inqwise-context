package com.inqwise.context;

import java.util.List;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.data.FlowIdService.CodecProfile;
import io.vertx.core.MultiMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpContextCarrierTest {
    @ParameterizedTest
    @ValueSource(strings = {"\uD800", "\uDC00", "before\uD800after", "\uDC00\uD800"})
    void set_malformedText_rejectsWithoutReplacingHeaders(String value) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.set("application", "retained");
        HttpContextCarrier carrier = new HttpContextCarrier(headers);
        carrier.set("flow_id", "original");

        assertThrows(IllegalArgumentException.class, () -> carrier.set("flow_id", value));
        assertEquals(List.of("original"), carrier.getAll("flow_id"));
        assertEquals("retained", headers.get("application"));
        assertEquals(2, headers.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\uD800\uDC00", "before\uD83D\uDE80after"})
    void set_validUnicode_roundTripsWithoutReplacement(String value) {
        HttpContextCarrier carrier = new HttpContextCarrier(MultiMap.caseInsensitiveMultiMap());
        carrier.set("flow_id", value);
        assertEquals(List.of(value), carrier.getAll("flow_id"));
    }

    @ParameterizedTest
    @EnumSource(CodecProfile.class)
    void carrier_allProfiles_roundTripAsAscii(CodecProfile profile) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        HttpContextCarrier carrier = new HttpContextCarrier(headers);
        FlowIdService codec = new FlowIdService(profile);
        FlowId original = FlowId.builder().withId("unicode:\u0394\nquoted-\"id\"").build();
        String encoded = codec.marshalToString(original);

        carrier.set("flow_id", encoded);

        String wire = headers.get(HttpContextCarrier.name("flow_id"));
        assertTrue(wire.chars().allMatch(value -> value >= 32 && value <= 126));
        assertEquals(List.of(encoded), carrier.getAll("flow_id"));
        assertEquals(original.toJson(), ((FlowId) codec.unmarshalFormString(carrier.getAll("flow_id").getFirst())).toJson());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2:YWJj", "1:%%%", "1:_w", "unversioned"})
    void carrier_invalidEnvelope_rejectsWithoutChangingOtherHeaders(String value) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.set(HttpContextCarrier.name("flow_id"), value).set("application", "retained");
        HttpContextCarrier carrier = new HttpContextCarrier(headers);

        assertThrows(IllegalArgumentException.class, () -> carrier.getAll("flow_id"));
        assertEquals("retained", headers.get("application"));
    }

    @Test
    void carrier_duplicates_preservesAllValuesForEngineValidation() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        HttpContextCarrier carrier = new HttpContextCarrier(headers);
        carrier.set("flow_id", "first");
        headers.add(HttpContextCarrier.name("flow_id").toUpperCase(java.util.Locale.ROOT), "1:c2Vjb25k");

        assertEquals(List.of("first", "second"), carrier.getAll("flow_id"));
        carrier.remove("flow_id");
        assertTrue(carrier.getAll("flow_id").isEmpty());
    }

    @Test
    void carrier_valueAndAggregateLimits_boundHeadersWithoutTruncation() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        HttpContextCarrier carrier = new HttpContextCarrier(headers);
        String maximum = "x".repeat(1534);
        carrier.set("flow_id", maximum);
        assertEquals(HttpContextCarrier.MAX_VALUE, headers.get(HttpContextCarrier.name("flow_id")).length());
        carrier.set("flow_id", maximum);
        assertEquals(List.of(maximum), carrier.getAll("flow_id"));
        assertThrows(IllegalArgumentException.class, () -> carrier.set("flow_id", maximum + "x"));
        carrier.remove("flow_id");
        carrier.set("first", "x".repeat(1400));
        carrier.set("second", "x".repeat(1400));
        assertThrows(IllegalArgumentException.class, () -> carrier.set("third", "x".repeat(400)));
        assertTrue(carrier.getAll("third").isEmpty());
        assertFalse(carrier.getAll("first").isEmpty());
        headers.set(HttpContextCarrier.name("first"), "x".repeat(HttpContextCarrier.MAX_TOTAL));
        assertThrows(IllegalArgumentException.class, () -> carrier.getAll("second"));
    }
}
