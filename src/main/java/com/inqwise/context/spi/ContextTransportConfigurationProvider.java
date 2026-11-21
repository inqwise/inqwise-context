package com.inqwise.context.spi;

import com.inqwise.context.ContextTransportConfiguration;

/**
 * Supplies the application default transport configuration through
 * {@link java.util.ServiceLoader}. Register at most one implementation per
 * library classloader. Configuration discovery is separate from codec discovery.
 */
public interface ContextTransportConfigurationProvider {
    /**
     * Supplies an immutable default, evaluated once during lazy resolution.
     * Implementations must not capture request data or execution contexts.
     *
     * @return a non-null configuration
     */
    ContextTransportConfiguration configuration();
}
