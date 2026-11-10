package com.inqwise.context.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.inqwise.context.data.FlowIdService.CodecProfile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class FlowIdServiceTest {

	private final FlowIdService service = new FlowIdService();

	@Test
	void marshalAndUnmarshalRoundTrip() {
		FlowId original = FlowId.builder()
			.withId("request-123")
			.withTimestamp(Instant.parse("2024-10-20T12:34:56Z"))
			.build();

		String encoded = service.marshalToString(original);
		FlowId decoded = (FlowId) service.unmarshalFormString(encoded);

		assertEquals(original.getId(), decoded.getId());
		assertEquals(original.getTimestamp(), decoded.getTimestamp());
	}

	@Test
	void marshalOmitsNulls() {
		FlowId minimal = FlowId.builder()
			.withId("id-only")
			.build();

		String encoded = service.marshalToString(minimal);
		FlowId decoded = (FlowId) service.unmarshalFormString(encoded);

		assertEquals("id-only", decoded.getId());
		assertNull(decoded.getTimestamp());
	}

	@Test
	void keyMatchesExpectedHeader() {
		assertEquals("flow_id", service.key());
	}

    @ParameterizedTest
    @MethodSource("profiles")
    void transport_allWriterReaderProfiles_preserveFlowValues(CodecProfile writer, CodecProfile reader) {
        FlowId original = FlowId.builder().withId("request: \"quoted\"\\path\n\u0394")
            .withTimestamp(Instant.parse("2026-11-08T10:00:00.123Z")).build();
        String encoded = new FlowIdService(writer).marshalToString(original);

        FlowId decoded = (FlowId) new FlowIdService(reader).unmarshalFormString(encoded);

        assertNotSame(original, decoded);
        assertEquals(original.getId(), decoded.getId());
        assertEquals(original.getTimestamp(), decoded.getTimestamp());
    }

    @ParameterizedTest
    @EnumSource(CodecProfile.class)
    void transport_nullFields_remainAbsent(CodecProfile profile) {
        FlowIdService codec = new FlowIdService(profile);
        FlowId decoded = (FlowId) codec.unmarshalFormString(codec.marshalToString(FlowId.builder().build()));
        assertTrue(decoded.toJson().isEmpty());
    }

    @Test
    void marshal_legacyProfile_preservesExistingWireFormat() {
        assertEquals("eyJpZCI6ImxlZ2FjeSJ9", new FlowIdService(CodecProfile.LEGACY)
            .marshalToString(FlowId.builder().withId("legacy").build()));
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void removal_allWriterReaderProfiles_recognizeVersionedMarker(CodecProfile writer, CodecProfile reader) {
        String removal = new FlowIdService(writer).marshalRemovalToString();
        assertEquals("flow-id:1:remove:", removal);
        assertTrue(new FlowIdService(reader).isRemoval(removal));
        assertFalse(new FlowIdService(reader).isRemoval(
            new FlowIdService(writer).marshalToString(FlowId.builder().withId(removal).build())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"flow-id:2:remove:", "flow-id:1:remove:extra", "flow-id:1:remove", ""})
    void removal_invalidMarker_isNotTreatedAsDeletion(String encoded) {
        assertFalse(service.isRemoval(encoded));
        assertThrows(RuntimeException.class, () -> service.unmarshalFormString(encoded));
    }

    @Test
    void marshal_jsonProfile_producesReadableVersionedEnvelope() {
        assertEquals("flow-id:1:json:{\"id\":\"debug\"}", new FlowIdService(CodecProfile.JSON)
            .marshalToString(FlowId.builder().withId("debug").build()));
    }

    @Test
    void marshal_gzipProfile_compressesBeforeBase64Encoding() {
        FlowId original = FlowId.builder().withId("repeated-".repeat(100)).build();
        String encoded = new FlowIdService(CodecProfile.GZIP).marshalToString(original);
        assertTrue(encoded.startsWith("flow-id:1:gzip:"));
        assertTrue(encoded.length() < new FlowIdService(CodecProfile.JSON).marshalToString(original).length());
        byte[] compressed = Base64.getDecoder().decode(encoded.substring("flow-id:1:gzip:".length()));
        assertEquals(0x1f, compressed[0] & 0xff);
        assertEquals(0x8b, compressed[1] & 0xff);
    }

    @ParameterizedTest
    @ValueSource(strings = {"flow-id:2:json:{}", "flow-id:1:unknown:{}", "flow-id:",
        "flow-id:1:gzip:bm90LWd6aXA=", "flow-id:1:gzip:%%%", "%%%"})
    void unmarshal_invalidEnvelope_rejectsUnsupportedOrCorruptInput(String encoded) {
        assertThrows(IllegalArgumentException.class, () -> service.unmarshalFormString(encoded));
    }

    @ParameterizedTest
    @EnumSource(CodecProfile.class)
    void transport_payloadLimit_acceptsExactByteLimitAndRejectsOneByteMore(CodecProfile profile) throws IOException {
        FlowIdService codec = new FlowIdService(profile);
        FlowId maximum = FlowIdTestSupport.maximumPayload();
        assertEquals(64 * 1024, maximum.toJson().toBuffer().length());
        assertEquals(maximum.toJson(), ((FlowId) codec.unmarshalFormString(codec.marshalToString(maximum))).toJson());

        FlowId oversized = FlowId.builderFrom(maximum).withId(maximum.getId() + "x").build();
        byte[] payload = oversized.toJson().toBuffer().getBytes();
        assertEquals(64 * 1024 + 1, payload.length);
        assertThrows(IllegalArgumentException.class, () -> codec.marshalToString(oversized));
        String encoded = switch (profile) {
            case LEGACY -> Base64.getEncoder().encodeToString(payload);
            case JSON -> "flow-id:1:json:" + new String(payload, StandardCharsets.UTF_8);
            case GZIP -> gzipHeader(payload);
        };
        assertThrows(IllegalArgumentException.class, () -> codec.unmarshalFormString(encoded));
    }

    @Test
    void unmarshal_gzipAtPayloadLimit_validatesTrailer() throws IOException {
        String prefix = "flow-id:1:gzip:";
        String valid = gzipHeader(FlowIdTestSupport.maximumPayload().toJson().toBuffer().getBytes());
        byte[] compressed = Base64.getDecoder().decode(valid.substring(prefix.length()));
        byte[] truncated = Arrays.copyOf(compressed, compressed.length - 1);
        compressed[compressed.length - 8] ^= 1; // Damage the CRC without changing the inflated data.

        assertThrows(IllegalArgumentException.class,
            () -> service.unmarshalFormString(prefix + Base64.getEncoder().encodeToString(truncated)));
        assertThrows(IllegalArgumentException.class,
            () -> service.unmarshalFormString(prefix + Base64.getEncoder().encodeToString(compressed)));
    }

    @ParameterizedTest
    @EnumSource(CodecProfile.class)
    void marshal_oversizedItem_rejectsBeforeEncoding(CodecProfile profile) {
        FlowId large = FlowId.builder().withId("x".repeat(64 * 1024)).build();
        assertThrows(IllegalArgumentException.class, () -> new FlowIdService(profile).marshalToString(large));
    }

    @Test
    void unmarshal_compressedOversizedItem_boundsInflatedPayload() throws Exception {
        String encoded = gzipHeader(("{\"id\":\"" + "x".repeat(128 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> service.unmarshalFormString(encoded));
    }

    private static String gzipHeader(byte[] payload) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(payload);
        }
        return "flow-id:1:gzip:" + Base64.getEncoder().encodeToString(output.toByteArray());
    }

    @Test
    void unmarshal_oversizedHeaderOrLegacyPayload_rejectsWithoutUnboundedDecode() {
        assertThrows(IllegalArgumentException.class, () -> service.unmarshalFormString("x".repeat(128 * 1024 + 1)));
        String encoded = Base64.getEncoder().encodeToString("x".repeat(64 * 1024 + 1).getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> service.unmarshalFormString(encoded));
    }

    @Test
    void constructorOrDecoder_nullArgument_rejects() {
        assertThrows(NullPointerException.class, () -> new FlowIdService(null));
        assertThrows(NullPointerException.class, () -> service.unmarshalFormString(null));
    }

    @Test
    void constructor_configuredProfile_isCapturedWithoutChangingReaderSupport() {
        String previous = System.getProperty(FlowIdService.CODEC_PROPERTY);
        try {
            System.clearProperty(FlowIdService.CODEC_PROPERTY);
            assertEquals("eyJpZCI6ImxlZ2FjeSJ9", new FlowIdService()
                .marshalToString(FlowId.builder().withId("legacy").build()));
            System.setProperty(FlowIdService.CODEC_PROPERTY, "json");
            FlowIdService configured = new FlowIdService();
            System.setProperty(FlowIdService.CODEC_PROPERTY, "gzip");
            assertTrue(configured.marshalToString(FlowId.builder().build()).startsWith("flow-id:1:json:"));
            assertTrue(new FlowIdService().marshalToString(FlowId.builder().build()).startsWith("flow-id:1:gzip:"));
            System.setProperty(FlowIdService.CODEC_PROPERTY, "unsupported");
            assertThrows(IllegalArgumentException.class, FlowIdService::new);
        } finally {
            if (previous == null) {
                System.clearProperty(FlowIdService.CODEC_PROPERTY);
            } else {
                System.setProperty(FlowIdService.CODEC_PROPERTY, previous);
            }
        }
        assertFalse(service.replaceExistingOnInbound());
    }

    private static Stream<Arguments> profiles() {
        return Arrays.stream(CodecProfile.values()).flatMap(writer -> Arrays.stream(CodecProfile.values())
            .map(reader -> Arguments.of(writer, reader)));
    }
}
