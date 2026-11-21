package com.inqwise.context;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.spi.ContextChainDataService;

import static com.inqwise.context.TransportClasspathTestSupport.check;

/** Forked first-use checks for metadata provider classloader selection. */
public final class ProviderClassLoaderNode {
    private ProviderClassLoaderNode() {
    }

    /** Initializes discovery with an unrelated TCCL, then runs core propagation. */
    public static void main(String[] args) throws Exception {
        String descriptor = "META-INF/services/" + ContextChainDataService.class.getName();
        Path plugin = Path.of(args[1]).resolve("plugin");
        Files.createDirectories(plugin.resolve(descriptor).getParent());
        Files.writeString(plugin.resolve(descriptor), PluginProvider.class.getName() + "\n");
        ClassLoader defining = ProviderClassLoaderNode.class.getClassLoader();
        try (URLClassLoader isolated = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader());
                URLClassLoader pluginLoader = new URLClassLoader(new URL[] {plugin.toUri().toURL()}, defining)) {
            ClassLoader caller = switch (args[0]) {
                case "isolated", "library" -> isolated;
                case "null" -> null;
                case "plugin" -> pluginLoader;
                case "throwing" -> new ClassLoader(defining) {
                    @Override
                    public Enumeration<URL> getResources(String name) throws IOException {
                        if (name.equals(descriptor)) {
                            throw new IllegalStateException("Unrelated TCCL must not enumerate metadata registrations");
                        }
                        return super.getResources(name);
                    }
                };
                default -> throw new IllegalArgumentException("Unknown classloader test mode");
            };
            List<ContextChainDataService> first;
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            try {
                thread.setContextClassLoader(caller);
                first = ContextChainStorageHolder.getDataProviders();
                Set<String> expected = args[0].equals("library") ? Set.of("flow_id", "plugin_only") : Set.of("flow_id");
                check(first.stream().map(ContextChainDataService::key).collect(Collectors.toSet()).equals(expected),
                    "First caller changed the library provider set");
                check(PluginProvider.constructed.get() == (args[0].equals("library") ? 1 : 0),
                    "Wrong provider registration was instantiated");
            } finally {
                thread.setContextClassLoader(previous);
            }
            check(ContextChainStorageHolder.getDataProviders() == first, "Discovery result was not cached");
            check(ContextChainStorageHolder.getDataProvider("flow_id") instanceof FlowIdService, "FlowId provider missing");
        }
        CoreOnlyTransportNode.main(new String[0]);
        System.out.println("PROVIDER_CLASSLOADER_OK");
    }

    /** Provider whose registration exists only in the plugin TCCL. */
    public static final class PluginProvider extends FlowIdService {
        static final AtomicInteger constructed = new AtomicInteger();

        /** Records whether discovery incorrectly visited the plugin-only registration. */
        public PluginProvider() {
            constructed.incrementAndGet();
        }

        @Override
        public String key() {
            return "plugin_only";
        }
    }
}
