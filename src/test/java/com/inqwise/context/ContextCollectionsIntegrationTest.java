package com.inqwise.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowIdService.CodecProfile;
import com.inqwise.context.extension.StringListContext;
import com.inqwise.context.extension.StringMapContext;
import com.inqwise.context.extension.StringSetContext;
import com.inqwise.context.spi.ContextChainDataService;
import com.inqwise.context.spi.ContextItemFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(40)
class ContextCollectionsIntegrationTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @EnumSource(CodecProfile.class)
    void collections_externalCodecAndFactorySpis_transportSnapshotsWithoutOptionalClients(CodecProfile profile) throws Exception {
        registerProviders();
        TransportClasspathTestSupport.runNode(ContextCollectionsNode.class, profile, directory, List.of(), "CONTEXT_COLLECTIONS_OK");
    }

    @ParameterizedTest
    @MethodSource("httpModes")
    void collections_httpRoundTrip_replacesPendingCallerCollectionsWithoutChangingFlowIdPolicy(
            CodecProfile profile, ContextCollectionsHttpNode.Protocol protocol)
            throws Exception {
        registerProviders();
        TransportClasspathTestSupport.runNode(ContextCollectionsHttpNode.class, profile, directory,
            List.of("vertx-web-client-", "vertx-web-common-", "vertx-uri-template-", "vertx-auth-common-"),
            "CONTEXT_COLLECTIONS_HTTP_OK", protocol.name(), directory.toString());
    }

    @ParameterizedTest
    @EnumSource(CodecProfile.class)
    void collections_kafkaForwarding_keepsProducerStateAndIsolatesConsumerChanges(CodecProfile profile) throws Exception {
        registerProviders();
        TransportClasspathTestSupport.runNode(ContextCollectionsKafkaNode.class, profile, directory,
            List.of("vertx-kafka-client-", "kafka-clients-", "lz4-java-", "snappy-java-", "zstd-jni-", "slf4j-api-"),
            "CONTEXT_COLLECTIONS_KAFKA_OK");
    }

    private void registerProviders() throws Exception {
        Path services = directory.resolve("runtime/META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(ContextChainDataService.class.getName()),
            StringMapContext.Codec.class.getName() + "\n" + StringSetContext.Codec.class.getName() + "\n"
                + StringListContext.Codec.class.getName() + "\n");
        Files.writeString(services.resolve(ContextItemFactory.class.getName()),
            StringMapContext.Factory.class.getName() + "\n" + StringSetContext.Factory.class.getName() + "\n"
                + StringListContext.Factory.class.getName() + "\n");
    }

    private static Stream<Arguments> httpModes() {
        return Stream.of(CodecProfile.values()).flatMap(profile -> Stream.of(ContextCollectionsHttpNode.Protocol.values())
            .map(protocol -> Arguments.of(profile, protocol)));
    }
}
