package com.inqwise.context.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonObject;

class FlowIdTest {

    @Test
    void jsonConstructor_sourceOrOutputMutation_doesNotChangeFlowId() {
        JsonObject source = new JsonObject().put(FlowId.Keys.ID, "original").put(FlowId.Keys.TIMESTAMP, 123L);
        FlowId flow = new FlowId(source);

        source.put(FlowId.Keys.ID, "source-changed");
        flow.toJson().put(FlowId.Keys.ID, "output-changed").put(FlowId.Keys.TIMESTAMP, 456L);

        assertEquals("original", flow.getId());
        assertEquals(Instant.ofEpochMilli(123L), flow.getTimestamp());
    }

    @Test
    void builder_reusedAfterBuild_doesNotChangePreviouslyBuiltValue() {
        FlowId.Builder builder = FlowId.builder().withId("first").withTimestamp(Instant.ofEpochMilli(123L));
        FlowId first = builder.build();
        FlowId second = builder.withId("second").withTimestamp(Instant.ofEpochMilli(456L)).build();
        assertEquals("first", first.getId());
        assertEquals(Instant.ofEpochMilli(123L), first.getTimestamp());
        assertEquals("second", second.getId());
    }

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

	@Test
	void jsonConstructorHandlesMissingTimestamp() {
		JsonObject json = new JsonObject().put(FlowId.Keys.ID, "json-only");

		FlowId flowId = new FlowId(json);

		assertEquals("json-only", flowId.getId());
		assertNull(flowId.getTimestamp());
	}

	@Test
	void toJsonOmitsNullTimestamp() {
		FlowId flowId = FlowId.builder()
			.withId("no-ts")
			.build();

		JsonObject json = flowId.toJson();

		assertEquals("no-ts", json.getString(FlowId.Keys.ID));
		assertFalse(json.containsKey(FlowId.Keys.TIMESTAMP));
	}

	@Test
	void toStringIncludesKeyDetails() {
		Instant timestamp = Instant.parse("2024-12-31T23:59:59Z");
		FlowId flowId = FlowId.builder()
			.withId("stringified")
			.withTimestamp(timestamp)
			.build();

		String text = flowId.toString();

		assertTrue(text.contains("stringified"));
		assertTrue(text.contains(timestamp.toString()));
	}

	@Test
	void keysClassIsInstantiable() {
		FlowId.Keys keys = new FlowId.Keys();

		assertNotNull(keys);
		assertEquals("id", FlowId.Keys.ID);
		assertEquals("timestamp", FlowId.Keys.TIMESTAMP);
	}
}
