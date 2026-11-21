package com.inqwise.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.inqwise.context.data.FlowIdService.CodecProfile;
import com.inqwise.context.spi.ContextChainDataService;
import com.inqwise.context.spi.ContextTransportConfigurationProvider;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Timeout(40)
class CustomMetadataTransportTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @EnumSource(CodecProfile.class)
    void http_customCodecAndConfigurationSpi_applyReplacementWithoutChangingFlowIdPolicy(CodecProfile profile) throws Exception {
        registerSpis();
        TransportClasspathTestSupport.runNode(CustomMetadataTransportNode.class, profile, directory,
            List.of("vertx-web-client-", "vertx-web-common-", "vertx-uri-template-", "vertx-auth-common-"),
            "CUSTOM_METADATA_TRANSPORT_OK");
    }

    @ParameterizedTest
    @EnumSource(CodecProfile.class)
    void kafka_customCodecAndConfigurationSpi_forwardChangesWithoutReturningConsumerState(CodecProfile profile) throws Exception {
        registerSpis();
        TransportClasspathTestSupport.runNode(CustomKafkaMetadataTransportNode.class, profile, directory,
            List.of("vertx-kafka-client-", "kafka-clients-", "lz4-java-", "snappy-java-", "zstd-jni-", "slf4j-api-"),
            "CUSTOM_KAFKA_METADATA_OK");
    }

    private void registerSpis() throws Exception {
        Path services = directory.resolve("runtime/META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(ContextChainDataService.class.getName()),
            CustomMetadataTestProvider.class.getName() + "\n");
        Files.writeString(services.resolve(ContextTransportConfigurationProvider.class.getName()),
            CustomMetadataTestProvider.Configuration.class.getName() + "\n");
    }
}
