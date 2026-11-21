package com.inqwise.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowIdService.CodecProfile;
import io.vertx.core.spi.JsonFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(40)
class ExactIntegerFlowIdTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @MethodSource("integerModes")
    void decoding_configuredExactIntegerJsonCodec_preservesValidWireTimestamps(CodecProfile profile, String mode) throws Exception {
        Path descriptor = directory.resolve("runtime/META-INF/services/" + JsonFactory.class.getName());
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, ExactIntegerFlowIdNode.Factory.class.getName() + "\n");
        TransportClasspathTestSupport.runNode(ExactIntegerFlowIdNode.class, profile, directory,
            List.of(), "EXACT_INTEGER_FLOW_ID_OK", profile.name(), mode);
    }

    private static Stream<Arguments> integerModes() {
        return Arrays.stream(CodecProfile.values()).flatMap(profile -> Stream.of("big", "narrow")
            .map(mode -> Arguments.of(profile, mode)));
    }
}
