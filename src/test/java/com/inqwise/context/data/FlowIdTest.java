package com.inqwise.context.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonObject;

class FlowIdTest {

	@Test
	void builderPopulatesJson() {
		Instant timestamp = Instant.parse("2024-03-18T10:15:30Z");
		FlowId flowId = FlowId.builder()
			.withId("trace-42")
			.withTimestamp(timestamp)
			.build();

		JsonObject json = flowId.toJson();

		assertEquals("trace-42", json.getString(FlowId.Keys.ID));
		assertEquals(timestamp.toEpochMilli(), json.getLong(FlowId.Keys.TIMESTAMP));
	}

	@Test
	void builderFromCopiesValues() {
		Instant originalTimestamp = Instant.parse("2025-01-01T00:00:00Z");
		FlowId source = FlowId.builder()
			.withId("origin")
			.withTimestamp(originalTimestamp)
			.build();

		FlowId copy = FlowId.builderFrom(source)
			.withId("mutated")
			.build();

		assertEquals("mutated", copy.getId());
		assertEquals(originalTimestamp, copy.getTimestamp());
		assertEquals("origin", source.getId());
		assertEquals(originalTimestamp, source.getTimestamp());
	}
}
