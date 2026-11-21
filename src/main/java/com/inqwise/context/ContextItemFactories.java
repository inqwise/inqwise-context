package com.inqwise.context;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Supplier;

import com.inqwise.context.spi.ContextChainDataItem;
import com.inqwise.context.spi.ContextItemFactory;

final class ContextItemFactories {
    private ContextItemFactories() {
    }

    private record Binding(ContextSlot<?> slot, ContextItemFactory<?> provider) {
    }

    private record Resolution(Map<String, Binding> providers, IllegalStateException failure) {
        Map<String, Binding> get() {
            if (failure != null) {
                throw failure;
            }
            return providers;
        }
    }

    private static final class Defaults {
        private static final Resolution resolution = discover();
    }

    static <T extends ContextChainDataItem> Supplier<? extends T> factory(ContextSlot<T> slot,
            Supplier<? extends T> fallback) {
        Binding binding = Defaults.resolution.get().get(slot.key());
        if (binding != null) {
            if (binding.slot().type() != slot.type()) {
                throw new IllegalStateException("Context item factory type does not match the slot declaration");
            }
            return () -> slot.type().cast(binding.provider().create());
        }
        if (fallback == null) {
            throw new IllegalStateException("No factory is configured for this context item");
        }
        return fallback;
    }

    private static Resolution discover() {
        try {
            Map<String, Binding> providers = new HashMap<>();
            for (ContextItemFactory<?> provider : ServiceLoader.load(ContextItemFactory.class,
                    ContextItemFactories.class.getClassLoader())) {
                ContextSlot<?> slot = Objects.requireNonNull(provider.slot(), "Factory slot is mandatory");
                if (providers.putIfAbsent(slot.key(), new Binding(slot, provider)) != null) {
                    return new Resolution(null, new IllegalStateException("Multiple factories registered for a context item"));
                }
            }
            return new Resolution(Map.copyOf(providers), null);
        } catch (RuntimeException | ServiceConfigurationError | LinkageError | AssertionError failure) {
            return new Resolution(null, new IllegalStateException(
                "Cannot resolve context item factories (" + failure.getClass().getName() + ")"));
        }
    }
}
