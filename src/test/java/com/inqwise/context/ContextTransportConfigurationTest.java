package com.inqwise.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextTransportConfigurationTest {
    @Test
    void builder_snapshots_areImmutableAndIndependent() {
        var builder = ContextTransportConfiguration.builder().include("flow_id").include("flow_id");
        var first = builder.build();
        var second = builder.include("custom").build();

        assertEquals(Set.of("flow_id"), first.includedKeys());
        assertEquals(List.of("flow_id", "custom"), new ArrayList<>(second.includedKeys()));
        assertThrows(UnsupportedOperationException.class, () -> first.includedKeys().clear());
    }

    @Test
    void builder_emptyConfiguration_selectsNoMetadata() {
        assertTrue(ContextTransportConfiguration.builder().build().includedKeys().isEmpty());
    }

    @Test
    void include_invalidKeys_rejectsWithoutChangingBuilder() {
        var builder = ContextTransportConfiguration.builder();
        assertThrows(NullPointerException.class, () -> builder.include((String) null));
        assertThrows(NullPointerException.class, () -> builder.include((ContextSlot<?>) null));
        assertThrows(IllegalArgumentException.class, () -> builder.include(""));
        assertThrows(IllegalArgumentException.class, () -> builder.include(" \t\n"));
        assertTrue(builder.build().includedKeys().isEmpty());
    }

    @Test
    void resolveConfiguration_explicitOverride_preservesInstance() {
        var explicit = ContextTransportConfiguration.builder().include("custom").build();
        assertSame(explicit, ContextTransport.resolveConfiguration(explicit));
    }
}
