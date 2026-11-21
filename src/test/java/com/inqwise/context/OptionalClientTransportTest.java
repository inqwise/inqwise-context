package com.inqwise.context;

import java.nio.file.Path;
import java.util.List;

import com.inqwise.context.data.FlowIdService.CodecProfile;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Timeout(40)
class OptionalClientTransportTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @EnumSource(CodecProfile.class)
    void httpOnlyClasspath_allCodecProfiles_workWithoutKafka(CodecProfile profile) throws Exception {
        TransportClasspathTestSupport.runNode(HttpOnlyTransportNode.class, profile, directory,
            List.of("vertx-web-client-", "vertx-web-common-", "vertx-uri-template-", "vertx-auth-common-"),
            "HTTP_ONLY_TRANSPORT_OK");
    }

    @ParameterizedTest
    @EnumSource(CodecProfile.class)
    void kafkaOnlyClasspath_allCodecProfiles_workWithoutHttp(CodecProfile profile) throws Exception {
        TransportClasspathTestSupport.runNode(KafkaOnlyTransportNode.class, profile, directory,
            List.of("vertx-kafka-client-", "kafka-clients-", "lz4-java-", "snappy-java-", "zstd-jni-", "slf4j-api-"),
            "KAFKA_ONLY_TRANSPORT_OK");
    }
}
