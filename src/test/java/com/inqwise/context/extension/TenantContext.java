package com.inqwise.context.extension;

import com.inqwise.context.ContextSlot;
import com.inqwise.context.ContextSlots;
import com.inqwise.context.spi.ContextChainDataItem;
import com.inqwise.context.spi.ContextChainDataService;
import com.inqwise.context.spi.ContextItemFactory;

/** External-package fixture that can use only the public context API. */
public final class TenantContext {
    /** Typed application metadata. */
    public record Tenant(String id) implements ContextChainDataItem {
    }

    /** Single declaration shared by application access, codec, and factory. */
    public static final ContextSlot<Tenant> VALUE = ContextSlots.define("tenant", Tenant.class, () -> new Tenant("default"));

    private TenantContext() {
    }

    /** Application-owned codec using the declaration's identifier. */
    public static final class Codec implements ContextChainDataService {
        @Override
        public String key() {
            return VALUE.key();
        }

        @Override
        public String marshalToString(ContextChainDataItem item) {
            return VALUE.type().cast(item).id();
        }

        @Override
        public Tenant unmarshalFormString(String text) {
            return new Tenant(text);
        }
    }

    /** Environment-owned initial value, registered through the factory SPI. */
    public static final class Factory implements ContextItemFactory<Tenant> {
        @Override
        public ContextSlot<Tenant> slot() {
            return VALUE;
        }

        @Override
        public Tenant create() {
            return new Tenant("configured");
        }
    }
}
