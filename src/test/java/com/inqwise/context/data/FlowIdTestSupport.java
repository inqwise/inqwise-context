package com.inqwise.context.data;

import java.time.Instant;

public final class FlowIdTestSupport {
    private FlowIdTestSupport() {
    }

    public static FlowId maximumPayload() {
        FlowId prefix = FlowId.builder().withId("edge:\u0394\n\"")
            .withTimestamp(Instant.parse("2026-11-10T10:00:00.123Z")).build();
        int remaining = 64 * 1024 - prefix.toJson().toBuffer().length();
        return FlowId.builderFrom(prefix)
            .withId(prefix.getId() + "\u0394".repeat(remaining / 2) + "x".repeat(remaining % 2)).build();
    }
}
