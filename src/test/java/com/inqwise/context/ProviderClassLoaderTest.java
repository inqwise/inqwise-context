package com.inqwise.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.inqwise.context.data.FlowIdService.CodecProfile;
import com.inqwise.context.spi.ContextChainDataService;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(40)
class ProviderClassLoaderTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"isolated", "null", "plugin", "throwing", "library"})
    void discovery_firstCallerThreadClassLoader_doesNotChangeLibraryProviders(String mode) throws Exception {
        if (mode.equals("library")) {
            Path descriptor = directory.resolve("runtime/META-INF/services/" + ContextChainDataService.class.getName());
            Files.createDirectories(descriptor.getParent());
            Files.writeString(descriptor, ProviderClassLoaderNode.PluginProvider.class.getName() + "\n");
        }
        TransportClasspathTestSupport.runNode(ProviderClassLoaderNode.class, CodecProfile.LEGACY, directory,
            List.of(), "PROVIDER_CLASSLOADER_OK", mode, directory.toString());
    }
}
