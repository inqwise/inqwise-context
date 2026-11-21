package com.inqwise.context;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import com.inqwise.context.spi.ContextTransportCarrier;
import io.vertx.core.MultiMap;

final class HttpContextCarrier implements ContextTransportCarrier {
    static final String PREFIX = "inqwise-context-";
    static final int MAX_VALUE = 2048;
    static final int MAX_TOTAL = 4096;
    private final MultiMap headers;

    HttpContextCarrier(MultiMap headers) {
        this.headers = headers;
    }

    static String name(String key) {
        if (key.length() > 128 || !key.matches("[a-zA-Z0-9!#$%&'*+.^_`|~-]+")) {
            throw new IllegalArgumentException("Metadata key is not supported in HTTP headers");
        }
        return PREFIX + key.toLowerCase(Locale.ROOT);
    }

    static void clear(MultiMap headers) {
        List<String> names = headers.names().stream().filter(HttpContextCarrier::managed).toList();
        names.forEach(headers::remove);
    }

    static boolean hasMetadata(MultiMap headers) {
        return headers.names().stream().anyMatch(HttpContextCarrier::managed);
    }

    private static boolean managed(String name) {
        return name.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    @Override
    public List<String> getAll(String key) {
        List<String> values = headers.getAll(name(key));
        if (values.isEmpty()) {
            return List.of();
        }
        checkTotal(null, 0);
        return values.stream().map(HttpContextCarrier::decode).toList();
    }

    private static String decode(String encoded) {
        if (encoded.length() > MAX_VALUE || !encoded.startsWith("1:")) {
            throw new IllegalArgumentException("Invalid HTTP metadata envelope");
        }
        byte[] bytes = Base64.getUrlDecoder().decode(encoded.substring(2));
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Invalid HTTP metadata text");
        }
    }

    @Override
    public void set(String key, String value) {
        if (value.length() > MAX_VALUE) {
            throw new IllegalArgumentException("HTTP metadata exceeds size limit");
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException("Invalid HTTP metadata text");
        }
        String encoded = "1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        if (encoded.length() > MAX_VALUE) {
            throw new IllegalArgumentException("HTTP metadata exceeds size limit");
        }
        String name = name(key);
        checkTotal(name, name.length() + encoded.length() + 4);
        headers.set(name, encoded);
    }

    private void checkTotal(String replacing, int extra) {
        int total = extra;
        for (var header : headers) {
            if (managed(header.getKey()) && !header.getKey().equalsIgnoreCase(replacing)) {
                total += header.getKey().length() + header.getValue().length() + 4;
                if (total > MAX_TOTAL) {
                    throw new IllegalArgumentException("HTTP metadata exceeds total size limit");
                }
            }
        }
    }

    @Override
    public void remove(String key) {
        headers.remove(name(key));
    }
}
