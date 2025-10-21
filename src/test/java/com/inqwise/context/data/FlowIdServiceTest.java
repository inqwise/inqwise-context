package com.inqwise.context.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class FlowIdServiceTest {

	private final FlowIdService service = new FlowIdService();

	@Test
	void marshalAndUnmarshalRoundTrip() {
		FlowId original = FlowId.builder()
			.withId("request-123")
			.withTimestamp(Instant.parse("2024-10-20T12:34:56Z"))
			.build();

		String encoded = service.marshalToString(original);
		FlowId decoded = (FlowId) service.unmarshalFormString(encoded);

		assertEquals(original.getId(), decoded.getId());
		assertEquals(original.getTimestamp(), decoded.getTimestamp());
	}

	@Test
	void marshalOmitsNulls() {
		FlowId minimal = FlowId.builder()
			.withId("id-only")
			.build();

		String encoded = service.marshalToString(minimal);
		FlowId decoded = (FlowId) service.unmarshalFormString(encoded);

		assertEquals("id-only", decoded.getId());
		assertNull(decoded.getTimestamp());
	}

	@Test
	void keyMatchesExpectedHeader() {
		assertEquals("flow_id", service.key());
	}
}
