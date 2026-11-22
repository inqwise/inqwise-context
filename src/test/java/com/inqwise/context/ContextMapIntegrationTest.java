package com.inqwise.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.inqwise.context.data.FlowIdService.CodecProfile;
import com.inqwise.context.extension.StringMapContext;
import com.inqwise.context.spi.ContextChainDataService;
import com.inqwise.context.spi.ContextItemFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Timeout(40)
class ContextMapIntegrationTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @EnumSource(CodecProfile.class)
    void map_externalCodecAndFactorySpis_transportSnapshotsWithoutOptionalClients(CodecProfile profile) throws Exception {
        Path services = directory.resolve("runtime/META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(ContextChainDataService.class.getName()), StringMapContext.Codec.class.getName() + "\n");
        Files.writeString(services.resolve(ContextItemFactory.class.getName()), StringMapContext.Factory.class.getName() + "\n");
        TransportClasspathTestSupport.runNode(ContextMapNode.class, profile, directory, List.of(), "CONTEXT_MAP_OK");
    }
}
