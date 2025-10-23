package com.inqwise.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryContext;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;

class EventBusTransportHandlerTest {

	private final EventBusTransportHandler handler = new EventBusTransportHandler();
	private final FlowIdService flowIdService = new FlowIdService();

	@Test
	void handleInboundWithoutContextStillAdvancesChain() {
		FlowId flowId = FlowId.builder()
			.withId("no-context")
			.build();
		String encoded = flowIdService.marshalToString(flowId);

		StubMessage message = new StubMessage(null);
		message.headers().add(flowIdService.key(), encoded);
		StubDeliveryContext delivery = new StubDeliveryContext(message);

		handler.handleInbound(delivery);

		assertTrue(delivery.isNextCalled());
		assertNull(Storage.get(flowIdService.key()));
	}

	@Test
	void handleInboundWithinContextPopulatesStorage() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			FlowId flowId = FlowId.builder()
				.withId("inbound")
				.withTimestamp(Instant.parse("2024-03-18T10:15:30Z"))
				.build();
			String encoded = flowIdService.marshalToString(flowId);

			StubMessage message = new StubMessage(null);
			message.headers().add(flowIdService.key(), encoded);
			StubDeliveryContext delivery = new StubDeliveryContext(message);

			CompletableFuture<FlowId> storedFuture = new CompletableFuture<>();
			vertx.getOrCreateContext().runOnContext(v -> {
				try {
					handler.handleInbound(delivery);
					storedFuture.complete((FlowId) Storage.get(flowIdService.key()));
				} catch (Throwable throwable) {
					storedFuture.completeExceptionally(throwable);
				}
			});
			FlowId stored = storedFuture.get(5, TimeUnit.SECONDS);
			assertEquals(flowId.getId(), stored.getId());
			assertEquals(flowId.getTimestamp(), stored.getTimestamp());
			assertTrue(delivery.isNextCalled());
		} finally {
			vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void handleOutboundWithinContextAddsHeaderWhenValuePresent() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			CompletableFuture<Void> completion = new CompletableFuture<>();
			vertx.getOrCreateContext().runOnContext(v -> {
				try {
					StubDeliveryContext firstDelivery = new StubDeliveryContext(new StubMessage(null));
					handler.handleOutbound(firstDelivery);
					assertTrue(firstDelivery.isNextCalled());
					assertTrue(firstDelivery.message().headers().isEmpty());

					FlowId flowId = FlowId.builder()
						.withId("outbound")
						.withTimestamp(Instant.parse("2024-03-18T10:15:30Z"))
						.build();
					Storage.put(flowIdService.key(), flowId);

					StubDeliveryContext secondDelivery = new StubDeliveryContext(new StubMessage(null));
					handler.handleOutbound(secondDelivery);
					String encoded = flowIdService.marshalToString(flowId);
					assertEquals(encoded, secondDelivery.message().headers().get(flowIdService.key()));
					assertTrue(secondDelivery.isNextCalled());
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

	private static final class StubDeliveryContext implements DeliveryContext<Object> {
		private final Message<Object> message;
		private boolean nextCalled;

		private StubDeliveryContext(Message<Object> message) {
			this.message = message;
		}

		@Override
		public Message<Object> message() {
			return message;
		}

		@Override
		public void next() {
			nextCalled = true;
		}

		@Override
		public boolean send() {
			return true;
		}

		@Override
		public Object body() {
			return message.body();
		}

		private boolean isNextCalled() {
			return nextCalled;
		}
	}

	private static final class StubMessage implements Message<Object> {
		private final MultiMap headers = MultiMap.caseInsensitiveMultiMap();
		private final Object body;

		private StubMessage(Object body) {
			this.body = body;
		}

		@Override
		public String address() {
			return "address";
		}

		@Override
		public MultiMap headers() {
			return headers;
		}

		@Override
		public Object body() {
			return body;
		}

		@Override
		public String replyAddress() {
			return null;
		}

		@Override
		public boolean isSend() {
			return true;
		}

		@Override
		public void reply(Object message, DeliveryOptions options) {
			throw new UnsupportedOperationException("reply not supported in stub");
		}

		@Override
		public <R> Future<Message<R>> replyAndRequest(Object message, DeliveryOptions options) {
			throw new UnsupportedOperationException("reply not supported in stub");
		}
	}
}
