package com.inqwise.context;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.data.FlowIdService.CodecProfile;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.JacksonCodec;
import io.vertx.core.spi.JsonFactory;
import io.vertx.core.spi.json.JsonCodec;

import static com.inqwise.context.TransportClasspathTestSupport.check;

/** Forked application using alternate exact integer representations in its JSON codec. */
public final class ExactIntegerFlowIdNode {
    private ExactIntegerFlowIdNode() {
    }

    /** Verifies exact integer timestamps without modifying the parent JVM's JSON codec. */
    public static void main(String[] args) throws Exception {
        TransportClasspathTestSupport.requireAbsent("com.fasterxml.jackson.databind.ObjectMapper", "org.junit.jupiter.api.Assertions");
        Factory.narrow = args[1].equals("narrow");
        Object sample = new JsonObject("{\"timestamp\":1}").getValue("timestamp");
        check(Factory.narrow ? sample instanceof Byte : sample instanceof BigInteger, "Custom JSON codec not installed");
        check(new JsonObject("{\"id\":\"text\"}").getValue("id") instanceof StringBuilder, "Custom text representation not installed");
        CodecProfile profile = CodecProfile.valueOf(args[0]);
        FlowIdService codec = new FlowIdService(profile);
        for (long timestamp : List.of(0L, -1L, 32767L, -32768L, 32768L, 2147483648L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            FlowId original = FlowId.builder().withId("exact-integer").withTimestamp(Instant.ofEpochMilli(timestamp)).build();
            FlowId decoded = (FlowId) codec.unmarshalFormString(codec.marshalToString(original));
            check(decoded.getId().equals(original.getId()), "Identifier changed");
            check(decoded.getTimestamp().equals(original.getTimestamp()), "Timestamp changed");
        }
        FlowId absent = (FlowId) codec.unmarshalFormString(codec.marshalToString(FlowId.builder().build()));
        check(absent.getTimestamp() == null, "Missing timestamp changed");
        FlowId nullable = (FlowId) codec.unmarshalFormString(header(profile, "null"));
        check(nullable.getTimestamp() == null, "Null timestamp changed");
        for (String timestamp : List.of("1.5", "-0.5", "1.0", "1e3", "1e20", "9223372036854775808", "-9223372036854775809")) {
            try {
                codec.unmarshalFormString(header(profile, timestamp));
                throw new AssertionError("Malformed timestamp was accepted");
            } catch (IllegalArgumentException expected) {
                // Invalid timestamps remain invalid with the alternate integer representation.
            }
        }
        System.out.println("EXACT_INTEGER_FLOW_ID_OK");
    }

    private static String header(CodecProfile profile, String timestamp) throws Exception {
        String json = "{\"timestamp\":" + timestamp + "}";
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        return switch (profile) {
            case LEGACY -> Base64.getEncoder().encodeToString(payload);
            case JSON -> "flow-id:1:json:" + json;
            case GZIP -> {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
                    gzip.write(payload);
                }
                yield "flow-id:1:gzip:" + Base64.getEncoder().encodeToString(output.toByteArray());
            }
        };
    }

    /** Registers the alternate JSON codec through Vert.x's supported SPI. */
    public static final class Factory implements JsonFactory {
        private static boolean narrow;

        /** Creates the service-loaded factory. */
        public Factory() {
        }

        @Override
        public JsonCodec codec() {
            return new JacksonCodec() {
                @Override
                public <T> T fromBuffer(Buffer buffer, Class<T> type) {
                    return exact(super.fromBuffer(buffer, type));
                }

                @Override
                public <T> T fromString(String text, Class<T> type) {
                    return exact(super.fromString(text, type));
                }

                @SuppressWarnings("unchecked")
                private <T> T exact(T decoded) {
                    if (decoded instanceof Map<?, ?> fields) {
                        if (fields.get("id") instanceof String text) {
                            ((Map<String, Object>) fields).put("id", new StringBuilder(text));
                        }
                        Object timestamp = fields.get("timestamp");
                        if (timestamp instanceof Integer || timestamp instanceof Long) {
                            long value = ((Number) timestamp).longValue();
                            Number exact;
                            if (!narrow) {
                                exact = BigInteger.valueOf(value);
                            } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                                exact = Byte.valueOf((byte) value);
                            } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                                exact = Short.valueOf((short) value);
                            } else {
                                exact = (Number) timestamp;
                            }
                            ((Map<String, Object>) fields).put("timestamp", exact);
                        }
                    }
                    return decoded;
                }
            };
        }
    }
}
