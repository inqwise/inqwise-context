package com.inqwise.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.inqwise.context.data.FlowIdService.CodecProfile;
import com.inqwise.context.spi.ContextItemFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(40)
class ContextItemFactoryIntegrationTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"custom", "invalid"})
    void publicApi_factorySpi_runsOnCallerContextAndSupportsExplicitBypass(String mode) throws Exception {
        Path service = directory.resolve("runtime/META-INF/services/" + ContextItemFactory.class.getName());
        Files.createDirectories(service.getParent());
        String provider = mode.equals("custom") ? ContextItemFactoryNode.Factory.class.getName()
            : ContextItemFactoryNode.InvalidFactory.class.getName();
        Files.writeString(service, provider + "\n");
        TransportClasspathTestSupport.runNode(ContextItemFactoryNode.class, CodecProfile.LEGACY, directory,
            List.of(), "ITEM_FACTORY_OK", mode);
    }
}
