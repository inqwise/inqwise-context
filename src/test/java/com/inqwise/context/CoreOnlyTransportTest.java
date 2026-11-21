package com.inqwise.context;

import java.nio.file.Path;
import java.util.List;

import com.inqwise.context.data.FlowIdService.CodecProfile;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Timeout(40)
class CoreOnlyTransportTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @EnumSource(CodecProfile.class)
    void coreOnlyClasspath_allCodecProfiles_workWithoutOptionalClients(CodecProfile profile) throws Exception {
        TransportClasspathTestSupport.runNode(CoreOnlyTransportNode.class, profile, directory, List.of(),
            "CORE_ONLY_TRANSPORT_OK");
    }
}
