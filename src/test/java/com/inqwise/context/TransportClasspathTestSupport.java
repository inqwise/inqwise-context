package com.inqwise.context;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.data.FlowIdService.CodecProfile;

final class TransportClasspathTestSupport {
    private static final List<String> coreArtifacts = List.of("vertx-core-", "netty-", "guava-", "failureaccess-",
        "listenablefuture-", "jspecify-", "j2objc-annotations-", "checker-qual-", "error_prone_annotations-",
        "commons-io-", "log4j-api-", "log4j-core-", "jackson-core-");

    private TransportClasspathTestSupport() {
    }

    static void runNode(Class<?> entryPoint, CodecProfile profile, Path directory, List<String> extraArtifacts,
            String successMarker, String... arguments) throws Exception {
        List<String> artifacts = new ArrayList<>(coreArtifacts);
        artifacts.addAll(extraArtifacts);
        String dependencies = Arrays.stream(System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path")).split(Pattern.quote(File.pathSeparator)))
            .map(Path::of).filter(path -> artifacts.stream().anyMatch(path.getFileName().toString()::startsWith))
            .map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        Path runtime = directory.resolve("runtime");
        Files.createDirectories(runtime);
        String classpath = runtime + File.pathSeparator
            + Path.of(entryPoint.getProtectionDomain().getCodeSource().getLocation().toURI())
            + File.pathSeparator + Path.of(ContextTransport.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            + File.pathSeparator + dependencies;
        Path log = directory.resolve(entryPoint.getSimpleName() + "-" + profile.name() + ".log");
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-D" + FlowIdService.CODEC_PROPERTY + "=" + profile.name().toLowerCase(Locale.ROOT),
            "-cp", classpath, entryPoint.getName()));
        command.addAll(List.of(arguments));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!child.waitFor(30, TimeUnit.SECONDS)) {
                throw new AssertionError("Isolated child timed out:\n" + Files.readString(log));
            }
            String output = Files.readString(log);
            if (child.exitValue() != 0 || !output.contains(successMarker)) {
                throw new AssertionError("Isolated child failed with exit " + child.exitValue() + ":\n" + output);
            }
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                if (!child.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Cannot terminate isolated child " + child.pid());
                }
            }
        }
    }

    static void requireAbsent(String... names) throws Exception {
        for (String name : names) {
            try {
                Class.forName(name);
                throw new AssertionError("Optional dependency leaked into the child: " + name);
            } catch (ClassNotFoundException expected) {
                // The child must prove that the parent test classpath has not leaked in.
            }
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
