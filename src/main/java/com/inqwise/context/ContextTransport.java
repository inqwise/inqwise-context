package com.inqwise.context;

import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import com.inqwise.context.spi.ContextTransportConfigurationProvider;
import com.inqwise.context.spi.ContextTransportCarrier;
import io.vertx.core.internal.ContextInternal;

/**
 * Resolves transport configuration and provides opt-in carrier read/write
 * operations. Each adapter should capture its configuration when installed.
 * Resolving configuration alone never installs an adapter or enables transport.
 */
public final class ContextTransport {
    private ContextTransport() {
    }

    private static final class Defaults {
        private static final Resolution resolution = discoverDefault();
    }

    private static final class Engine {
        private static final ContextTransportEngine instance = new ContextTransportEngine(ContextChainStorageHolder::getDataProviders);
    }

    private record Resolution(ContextTransportConfiguration configuration, IllegalStateException failure) {
        ContextTransportConfiguration get() {
            if (failure != null) {
                throw failure;
            }
            return configuration;
        }
    }

    /**
     * Returns the shared application default. A single SPI provider overrides
     * the built-in configuration, which includes only {@code flow_id}.
     * Resolution and failures are cached in this library's defining classloader,
     * not selected by the first caller's thread context classloader.
     *
     * @return the shared immutable configuration
     * @throws IllegalStateException if discovery is ambiguous, invalid, or fails
     */
    public static ContextTransportConfiguration defaultConfiguration() {
        return Defaults.resolution.get();
    }

    /**
     * Resolves explicit configuration before the SPI or built-in default.
     * Non-null overrides bypass default initialization entirely, including any
     * cached default discovery failure.
     *
     * @param override the explicit configuration, or null to use the default
     * @return the explicit instance or shared default
     * @throws IllegalStateException if the default is required but cannot resolve
     */
    public static ContextTransportConfiguration resolveConfiguration(ContextTransportConfiguration override) {
        return override != null ? override : defaultConfiguration();
    }

    /**
     * Writes allowed metadata to a carrier using the shared default configuration.
     *
     * @param carrier the outgoing message headers
     * @throws NullPointerException if the carrier is null
     * @throws IllegalStateException if called outside a Vert.x context or default resolution fails
     */
    public static void writeTo(ContextTransportCarrier carrier) {
        writeTo(carrier, null);
    }

    /**
     * Writes allowed metadata from the current context. Codec and carrier failures
     * are reported through its exception handler without escaping this call.
     * Explicit values and removals replace stale headers; absent storage entries
     * leave existing headers unchanged. Unselected keys are never accessed.
     *
     * @param carrier the outgoing message headers
     * @param configuration the explicit configuration, or null for the shared default
     * @throws NullPointerException if the carrier is null
     * @throws IllegalStateException if called outside a Vert.x context or default resolution fails
     */
    public static void writeTo(ContextTransportCarrier carrier, ContextTransportConfiguration configuration) {
        Objects.requireNonNull(carrier, "Carrier is mandatory");
        ContextInternal context = currentContext();
        Engine.instance.write(context, carrier, resolveConfiguration(configuration).includedKeys());
    }

    /**
     * Reads allowed metadata using the shared default configuration.
     *
     * @param carrier the incoming message headers
     * @throws NullPointerException if the carrier is null
     * @throws IllegalStateException if called outside a Vert.x context or default resolution fails
     */
    public static void readFrom(ContextTransportCarrier carrier) {
        readFrom(carrier, null);
    }

    /**
     * Imports allowed metadata into the current context using each provider's
     * replacement policy. Codec and carrier failures are reported without
     * escaping this call. This does not create an isolated request context or
     * rebind callbacks; adapters must establish the correct context first.
     *
     * @param carrier the incoming message headers
     * @param configuration the explicit configuration, or null for the shared default
     * @throws NullPointerException if the carrier is null
     * @throws IllegalStateException if called outside a Vert.x context or default resolution fails
     */
    public static void readFrom(ContextTransportCarrier carrier, ContextTransportConfiguration configuration) {
        Objects.requireNonNull(carrier, "Carrier is mandatory");
        ContextInternal context = currentContext();
        Engine.instance.read(context, carrier, resolveConfiguration(configuration).includedKeys());
    }

    private static ContextInternal currentContext() {
        ContextInternal context = ContextInternal.current();
        if (context == null) {
            throw new IllegalStateException("Context transport requires a calling Vert.x context");
        }
        return context;
    }

    private static Resolution discoverDefault() {
        try {
            var providers = ServiceLoader.load(ContextTransportConfigurationProvider.class,
                ContextTransport.class.getClassLoader()).stream().iterator();
            if (!providers.hasNext()) {
                return new Resolution(ContextTransportConfiguration.builder().include("flow_id").build(), null);
            }
            var provider = providers.next();
            if (providers.hasNext()) {
                return new Resolution(null, new IllegalStateException(
                    "Multiple transport configuration providers found; register at most one per library classloader"));
            }
            ContextTransportConfiguration configuration = provider.get().configuration();
            if (configuration == null) {
                return new Resolution(null, new IllegalStateException("Transport configuration provider returned null"));
            }
            return new Resolution(configuration, null);
        } catch (RuntimeException | ServiceConfigurationError | LinkageError | AssertionError failure) {
            // Configuration failures may contain credentials or private endpoints.
            return new Resolution(null, new IllegalStateException(
                "Cannot resolve default transport configuration (" + failure.getClass().getName() + ")"));
        }
    }
}
