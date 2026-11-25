package com.inqwise.context;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.record.SimpleRecord;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class KafkaCompressionCompatibilityTest {
    static Stream<Arguments> compressionCases() {
        List<Arguments> cases = new ArrayList<>();
        for (CompressionType type : List.of(CompressionType.SNAPPY, CompressionType.LZ4)) {
            for (FlowIdService.CodecProfile profile : FlowIdService.CodecProfile.values()) {
                for (boolean binary : List.of(false, true)) {
                    for (boolean direct : List.of(false, true)) {
                        cases.add(Arguments.of(type, profile, binary, direct));
                    }
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, {1}, binary={2}, direct={3}")
    @MethodSource("compressionCases")
    void compressedBatch_roundTrip_preservesPayloadHeadersAndFlowId(
            CompressionType type, FlowIdService.CodecProfile profile, boolean binary, boolean direct) {
        byte[] payload = new byte[128 * 1024];
        if (binary) {
            new Random(42).nextBytes(payload);
        } else {
            Arrays.fill(payload, (byte) 'a');
        }
        FlowId original = FlowId.builder().withId("compressed-flow").build();
        FlowIdService codec = new FlowIdService(profile);
        String managedHeader = KafkaContextCarrier.name(codec.key());
        byte[] encoded = codec.marshalToString(original).getBytes(StandardCharsets.UTF_8);
        SimpleRecord[] expected = {
            new SimpleRecord(42L, new byte[] {1, 2}, payload, new Header[] {
                new RecordHeader("application", new byte[] {0, -1, 3}),
                new RecordHeader(managedHeader, encoded)
            }),
            new SimpleRecord(43L, null, new byte[0], new Header[] {
                new RecordHeader(managedHeader, codec.marshalRemovalToString().getBytes(StandardCharsets.UTF_8))
            }),
            new SimpleRecord(44L, new byte[] {4}, null, new Header[0])
        };
        MemoryRecords compressed = MemoryRecords.withRecords(Compression.of(type).build(), expected);
        ByteBuffer source = compressed.buffer().duplicate();
        ByteBuffer wire = direct ? ByteBuffer.allocateDirect(source.remaining()) : ByteBuffer.allocate(source.remaining());
        wire.put(source).flip();
        MemoryRecords received = MemoryRecords.readableRecords(wire);
        received.firstBatch().ensureValid();
        assertEquals(type, received.firstBatch().compressionType());

        List<Record> actual = new ArrayList<>();
        received.records().forEach(actual::add);
        assertEquals(expected.length, actual.size());
        for (int index = 0; index < expected.length; index++) {
            Record record = actual.get(index);
            assertEquals(index, record.offset());
            assertEquals(expected[index].timestamp(), record.timestamp());
            assertEquals(expected[index].key(), record.key());
            assertEquals(expected[index].value(), record.value());
            assertEquals(expected[index].headers().length, record.headers().length);
            for (int header = 0; header < record.headers().length; header++) {
                assertEquals(expected[index].headers()[header].key(), record.headers()[header].key());
                assertArrayEquals(expected[index].headers()[header].value(), record.headers()[header].value());
            }
        }
        String imported = new String(actual.getFirst().headers()[1].value(), StandardCharsets.UTF_8);
        FlowId decoded = (FlowId) codec.unmarshalFormString(imported);
        assertNotSame(original, decoded);
        assertEquals(original.toJson(), decoded.toJson());
        assertTrue(codec.isRemoval(new String(actual.get(1).headers()[0].value(), StandardCharsets.UTF_8)));
    }
}
