package com.inqwise.context.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.inqwise.context.spi.ContextChainDataService;
import com.inqwise.context.spi.ContextChainDataItem;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/**
 * Transports immutable {@link FlowId} values under the {@code flow_id} key.
 * Writers select a codec profile; readers accept legacy and versioned profiles.
 * JSON timestamps use epoch milliseconds. Decoded payloads are limited to 64 KiB.
 */
public class FlowIdService implements ContextChainDataService {

    /** System property selecting the writer profile: legacy, json, or gzip. */
    public static final String CODEC_PROPERTY = "inqwise.context.flow-id.codec";

    private static final String PREFIX = "flow-id:";
    private static final String REMOVAL = PREFIX + "1:remove:";
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private final CodecProfile profile;

    /**
     * Supported writer profiles. All profiles use the same JSON item schema.
     */
    public enum CodecProfile {
        /** Unversioned Base64 JSON, compatible with older library readers. */
        LEGACY,
        /** Version 1 readable JSON for inspecting transport metadata. */
        JSON,
        /** Version 1 JSON compressed with gzip and then encoded as Base64. */
        GZIP
    }

    /**
     * Creates a provider using {@link #CODEC_PROPERTY}, defaulting to legacy.
     * The profile is captured at construction and the provider is thread-safe.
     *
     * @throws IllegalArgumentException if the configured profile is unsupported
     */
    public FlowIdService() {
        this(CodecProfile.valueOf(System.getProperty(CODEC_PROPERTY, "legacy").trim().toUpperCase(Locale.ROOT)));
    }

    /**
     * Creates a provider with an explicit writer profile.
     *
     * @param profile the encoding used for outgoing metadata
     * @throws NullPointerException if the profile is null
     */
    public FlowIdService(CodecProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile is mandatory");
    }

    @Override
    public String key() {
        return "flow_id";
    }

    @Override
    public boolean replaceExistingOnInbound() {
        return false;
    }

    @Override
    public String marshalRemovalToString() {
        return REMOVAL;
    }

    @Override
    public boolean isRemoval(String str) {
        return REMOVAL.equals(str);
    }

    @Override
    public String marshalToString(ContextChainDataItem model) {
        byte[] payload = ((FlowId) model).toJson().toBuffer().getBytes();
        checkSize(payload);
        return switch (profile) {
            case LEGACY -> Base64.getEncoder().encodeToString(payload);
            case JSON -> PREFIX + "1:json:" + new String(payload, StandardCharsets.UTF_8);
            case GZIP -> PREFIX + "1:gzip:" + Base64.getEncoder().encodeToString(compress(payload));
        };
    }

    @Override
    public ContextChainDataItem unmarshalFormString(String str) {
        Objects.requireNonNull(str, "serialized FlowId is mandatory");
        if (str.length() > MAX_PAYLOAD_BYTES * 2) {
            throw new IllegalArgumentException("FlowId transport header exceeds size limit");
        }
        byte[] payload;
        if (!str.startsWith(PREFIX)) {
            payload = Base64.getDecoder().decode(str);
        } else {
            String[] envelope = str.split(":", 4);
            if (envelope.length != 4 || !envelope[1].equals("1")) {
                throw new IllegalArgumentException("Unsupported FlowId envelope version");
            }
            payload = switch (envelope[2]) {
                case "json" -> envelope[3].getBytes(StandardCharsets.UTF_8);
                case "gzip" -> decompress(Base64.getDecoder().decode(envelope[3]));
                default -> throw new IllegalArgumentException("Unsupported FlowId codec");
            };
        }
        checkSize(payload);
        return new FlowId(new JsonObject(Buffer.buffer(payload)));
    }

    private static byte[] compress(byte[] payload) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
                gzip.write(payload);
            }
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot compress FlowId payload", error);
        }
    }

    private static byte[] decompress(byte[] payload) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
            byte[] decoded = gzip.readNBytes(MAX_PAYLOAD_BYTES + 1);
            checkSize(decoded);
            return decoded;
        } catch (IOException error) {
            throw new IllegalArgumentException("Invalid compressed FlowId payload", error);
        }
    }

    private static void checkSize(byte[] payload) {
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("FlowId payload exceeds 64 KiB limit");
        }
    }
}
