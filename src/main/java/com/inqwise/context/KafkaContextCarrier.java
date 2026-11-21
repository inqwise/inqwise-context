package com.inqwise.context;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.inqwise.context.spi.ContextTransportCarrier;
import io.vertx.core.buffer.Buffer;
import io.vertx.kafka.client.producer.KafkaHeader;

final class KafkaContextCarrier implements ContextTransportCarrier {
    static final String PREFIX = "inqwise-context-";
    static final int MAX_VALUE = 128 * 1024 + 2;
    static final int MAX_TOTAL = 256 * 1024;
    private final Supplier<List<KafkaHeader>> headers;

    KafkaContextCarrier(Supplier<List<KafkaHeader>> headers) {
        this.headers = headers;
    }

    static String name(String key) {
        if (key.length() > 128 || !StandardCharsets.UTF_8.newEncoder().canEncode(key)) {
            throw new IllegalArgumentException("Metadata key is not supported in Kafka headers");
        }
        return PREFIX + key;
    }

    static void clear(List<KafkaHeader> headers) {
        headers.removeIf(header -> header.key().startsWith(PREFIX));
    }

    @Override
    public List<String> getAll(String key) {
        List<KafkaHeader> values = headers.get();
        String name = name(key);
        List<String> result = new ArrayList<>();
        boolean checked = false;
        for (KafkaHeader header : values) {
            if (header.key().equals(name)) {
                Buffer value = header.value();
                if (value == null || value.length() > MAX_VALUE || value.length() < 2
                        || value.getByte(0) != '1' || value.getByte(1) != ':') {
                    throw new IllegalArgumentException("Invalid Kafka metadata envelope");
                }
                if (!checked) {
                    checkTotal(values, null, 0);
                    checked = true;
                }
                try {
                    result.add(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(value.getBytes(2, value.length()))).toString());
                } catch (CharacterCodingException failure) {
                    throw new IllegalArgumentException("Invalid Kafka metadata text");
                }
            }
        }
        return result;
    }

    @Override
    public void set(String key, String value) {
        if (value.length() > MAX_VALUE - 2) {
            throw new IllegalArgumentException("Kafka metadata exceeds size limit");
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException("Invalid Kafka metadata text");
        }
        Buffer encoded = Buffer.buffer("1:").appendString(value, "UTF-8");
        if (encoded.length() > MAX_VALUE) {
            throw new IllegalArgumentException("Kafka metadata exceeds size limit");
        }
        String name = name(key);
        List<KafkaHeader> values = headers.get();
        checkTotal(values, name, name.getBytes(StandardCharsets.UTF_8).length + encoded.length());
        values.removeIf(header -> header.key().equals(name));
        values.add(KafkaHeader.header(name, encoded));
    }

    private static void checkTotal(List<KafkaHeader> headers, String replacing, int extra) {
        long total = extra;
        for (KafkaHeader header : headers) {
            if (header.key().startsWith(PREFIX) && !header.key().equals(replacing)) {
                total += header.key().getBytes(StandardCharsets.UTF_8).length
                    + (header.value() == null ? 0 : header.value().length());
            }
            if (total > MAX_TOTAL) {
                throw new IllegalArgumentException("Kafka metadata exceeds total size limit");
            }
        }
    }

    @Override
    public void remove(String key) {
        String name = name(key);
        headers.get().removeIf(header -> header.key().equals(name));
    }
}
