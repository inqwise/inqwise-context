package com.inqwise.context;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.inqwise.context.data.FlowId;

import io.vertx.core.Vertx;

class DefaultStorageTest {

	private static final String FLOW_ID_KEY = "flow_id";

	@Test
	void putAndGetWithinVertxContext() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			FlowId value = FlowId.builder()
				.withId("contextual")
				.withTimestamp(Instant.parse("2024-10-21T10:15:30Z"))
				.build();

			CompletableFuture<Void> completion = new CompletableFuture<>();
			vertx.getOrCreateContext().runOnContext(v -> {
				try {
					Storage.put(FLOW_ID_KEY, value);
					FlowId retrieved = (FlowId) Storage.get(FLOW_ID_KEY);
					assertSame(value, retrieved);
					completion.complete(null);
				} catch (Throwable throwable) {
					completion.completeExceptionally(throwable);
				}
			});
			completion.get(5, TimeUnit.SECONDS);
		} finally {
			vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void putOutsideVertxContextDoesNotStoreValue() {
		FlowId value = FlowId.builder()
			.withId("outside")
			.build();

		Storage.put(FLOW_ID_KEY, value);

		assertNull(Storage.get(FLOW_ID_KEY));
	}
}
