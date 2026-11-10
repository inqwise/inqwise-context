package com.inqwise.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.spi.ContextChainDataItem;
import com.inqwise.context.spi.ContextChainDataService;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryContext;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.ReplyFailure;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(VertxExtension.class)
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
		assertThrows(IllegalStateException.class, () -> Storage.get(flowIdService.key()));
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

    @Test
    void handleOutbound_existingHeaders_replacesDuplicatesWithCurrentContext(Vertx vertx, VertxTestContext context) {
        vertx.runOnContext(ignored -> context.verify(() -> {
            FlowId current = FlowId.builder().withId("current").build();
            Storage.put(flowIdService.key(), current);
            StubMessage message = new StubMessage(null);
            message.headers().add(flowIdService.key(), "stale");
            message.headers().add(flowIdService.key(), "duplicate");
            message.headers().add("unrelated", "preserved");
            StubDeliveryContext delivery = new StubDeliveryContext(message);

            handler.handleOutbound(delivery);

            assertEquals(List.of(flowIdService.marshalToString(current)), message.headers().getAll(flowIdService.key()));
            assertEquals("preserved", message.headers().get("unrelated"));
            assertEquals(1, delivery.nextCalls);
            handler.handleOutbound(new StubDeliveryContext(message));
            assertEquals(1, message.headers().getAll(flowIdService.key()).size());
            context.completeNow();
        }));
    }

    @Test
    void handleOutbound_absentStorage_preservesExplicitHeaders(Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        vertx.runOnContext(ignored -> context.verify(() -> {
            StubMessage message = new StubMessage(null);
            message.headers().add(flowIdService.key(), "explicit-first");
            message.headers().add(flowIdService.key(), "explicit-second");
            StubDeliveryContext delivery = new StubDeliveryContext(message);

            handler.handleOutbound(delivery);

            assertEquals(List.of("explicit-first", "explicit-second"), message.headers().getAll(flowIdService.key()));
            assertNull(Storage.get(flowIdService.key()));
            assertEquals(1, delivery.nextCalls);
            context.completeNow();
        }));
    }

    @Test
    void handleOutbound_nullEncodedValue_reportsFailureWithoutSendingStaleHeader(Vertx vertx, VertxTestContext context) {
        List<Throwable> failures = new ArrayList<>();
        vertx.exceptionHandler(failures::add);
        FlowIdService provider = new FlowIdService() {
            @Override
            public String marshalToString(ContextChainDataItem model) {
                return null;
            }
        };
        EventBusTransportHandler isolated = new EventBusTransportHandler(() -> List.of(provider));
        vertx.runOnContext(ignored -> context.verify(() -> {
            FlowId original = FlowId.builder().withId("local").build();
            Storage.put(provider.key(), original);
            StubMessage message = new StubMessage(null);
            message.headers().add(provider.key(), "stale");
            StubDeliveryContext delivery = new StubDeliveryContext(message);

            isolated.handleOutbound(delivery);

            assertNull(message.headers().get(provider.key()));
            assertSame(original, Storage.get(provider.key()));
            assertEquals(1, failures.size());
            assertEquals(NullPointerException.class.getName(),
                ((ContextPropagationException) failures.getFirst()).getFailureType());
            assertEquals(1, delivery.nextCalls);
            context.completeNow();
        }));
    }

    @Test
    void handleInbound_malformedProvider_continuesWithFollowingProvider(Vertx vertx, VertxTestContext context) {
        EventBusTransportHandler isolated = new EventBusTransportHandler(
            () -> List.of(new FailingService(), flowIdService));
        vertx.runOnContext(ignored -> context.verify(() -> {
            FlowId expected = FlowId.builder().withId("valid").build();
            StubMessage message = new StubMessage(null);
            message.headers().add("broken", "invalid base64!");
            message.headers().add(flowIdService.key(), flowIdService.marshalToString(expected));
            StubDeliveryContext delivery = new StubDeliveryContext(message);

            isolated.handleInbound(delivery);

            assertNull(Storage.get("broken"));
            assertEquals("valid", ((FlowId) Storage.get(flowIdService.key())).getId());
            assertEquals(1, delivery.nextCalls);
            context.completeNow();
        }));
    }

    @Test
    void handleOutbound_failingProvider_continuesWithFollowingProvider(Vertx vertx, VertxTestContext context) {
        EventBusTransportHandler isolated = new EventBusTransportHandler(
            () -> List.of(new FailingService(), flowIdService));
        vertx.runOnContext(ignored -> context.verify(() -> {
            FlowId value = FlowId.builder().withId("valid").build();
            Storage.put("broken", value);
            Storage.put(flowIdService.key(), value);
            StubDeliveryContext delivery = new StubDeliveryContext(new StubMessage(null));

            isolated.handleOutbound(delivery);

            assertNull(delivery.message().headers().get("broken"));
            assertEquals(flowIdService.marshalToString(value), delivery.message().headers().get(flowIdService.key()));
            assertEquals(1, delivery.nextCalls);
            context.completeNow();
        }));
    }

    @Test
    void handleInbound_existingFlowId_keepsInstanceAndAdvancesEvenWithMalformedHeader(
            Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        vertx.runOnContext(ignored -> context.verify(() -> {
            FlowId original = FlowId.builder().withId("caller").build();
            Storage.put(flowIdService.key(), original);
            StubMessage message = new StubMessage(null);
            message.headers().add(flowIdService.key(), "flow-id:99:unknown:malformed");
            message.headers().add(flowIdService.key(), flowIdService.marshalRemovalToString());
            StubDeliveryContext delivery = new StubDeliveryContext(message);

            handler.handleInbound(delivery);

            assertSame(original, Storage.get(flowIdService.key()));
            assertEquals(1, delivery.nextCalls);
            context.completeNow();
        }));
    }

    @Test
    void handleInbound_removedFlow_skipsMalformedHeaderWithoutReportingFailure(
            Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        vertx.runOnContext(ignored -> context.verify(() -> {
            Storage.remove(flowIdService.key());
            StubMessage message = new StubMessage(null);
            message.headers().add(flowIdService.key(), "flow-id:99:unknown:malformed");
            message.headers().add(flowIdService.key(), flowIdService.marshalRemovalToString());
            StubDeliveryContext delivery = new StubDeliveryContext(message);

            handler.handleInbound(delivery);

            assertNull(Storage.get(flowIdService.key()));
            assertEquals(1, delivery.nextCalls);
            context.completeNow();
        }));
    }

    @Test
    void handleOutbound_removedItem_clearsDuplicateHeadersWithoutCallingCodec(
            Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        EventBusTransportHandler isolated = new EventBusTransportHandler(() -> List.of(new FailingService()));
        vertx.runOnContext(ignored -> context.verify(() -> {
            Storage.remove("broken");
            StubMessage message = new StubMessage(null);
            message.headers().add("broken", "stale");
            message.headers().add("broken", "duplicate");
            message.headers().add("unrelated", "preserved");
            StubDeliveryContext delivery = new StubDeliveryContext(message);

            isolated.handleOutbound(delivery);

            assertTrue(message.headers().getAll("broken").isEmpty());
            assertEquals("preserved", message.headers().get("unrelated"));
            assertEquals(1, delivery.nextCalls);
            context.completeNow();
        }));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void handleInbound_removalMarker_usesProviderReplacementPolicy(
            boolean replaceExisting, Vertx vertx, VertxTestContext context) {
        FlowIdService provider = new FlowIdService() {
            @Override
            public boolean replaceExistingOnInbound() {
                return replaceExisting;
            }
        };
        EventBusTransportHandler isolated = new EventBusTransportHandler(() -> List.of(provider));
        vertx.exceptionHandler(context::failNow);
        vertx.runOnContext(ignored -> context.verify(() -> {
            FlowId original = FlowId.builder().withId("local").build();
            Storage.put(provider.key(), original);
            StubMessage message = new StubMessage(null);
            message.headers().add(provider.key(), provider.marshalRemovalToString());
            StubDeliveryContext delivery = new StubDeliveryContext(message);

            isolated.handleInbound(delivery);

            if (replaceExisting) {
                assertNull(Storage.get(provider.key()));
            } else {
                assertSame(original, Storage.get(provider.key()));
            }
            isolated.handleOutbound(new StubDeliveryContext(message));
            assertEquals(replaceExisting ? provider.marshalRemovalToString() : provider.marshalToString(original),
                message.headers().get(provider.key()));
            assertEquals(1, delivery.nextCalls);
            context.completeNow();
        }));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void handleInbound_legacyProvider_retainsDefaultReplacementBehavior(
            boolean removed, Vertx vertx, VertxTestContext context) {
        ContextChainDataService legacyProvider = new ContextChainDataService() {
            @Override
            public String key() {
                return "custom";
            }

            @Override
            public String marshalToString(ContextChainDataItem model) {
                return flowIdService.marshalToString(model);
            }

            @Override
            public ContextChainDataItem unmarshalFormString(String str) {
                return flowIdService.unmarshalFormString(str);
            }
        };
        EventBusTransportHandler customHandler = new EventBusTransportHandler(() -> List.of(legacyProvider));
        vertx.runOnContext(ignored -> context.verify(() -> {
            assertNull(legacyProvider.marshalRemovalToString());
            assertEquals(false, legacyProvider.isRemoval(flowIdService.marshalRemovalToString()));
            Storage.put("custom", FlowId.builder().withId("old").build());
            if (removed) {
                Storage.remove("custom");
            }
            StubMessage message = new StubMessage(null);
            message.headers().add("custom", legacyProvider.marshalToString(FlowId.builder().withId("new").build()));
            StubDeliveryContext delivery = new StubDeliveryContext(message);

            customHandler.handleInbound(delivery);

            assertEquals("new", ((FlowId) Storage.get("custom")).getId());
            assertEquals(1, delivery.nextCalls);
            context.completeNow();
        }));
    }

    @Test
    void handleInbound_valueInstalledDuringDecode_doesNotOverwriteLocalInstance(
            Vertx vertx, VertxTestContext context) {
        FlowId local = FlowId.builder().withId("local").build();
        FlowIdService delayedProvider = new FlowIdService() {
            @Override
            public ContextChainDataItem unmarshalFormString(String str) {
                Storage.put(key(), local);
                return super.unmarshalFormString(str);
            }
        };
        EventBusTransportHandler customHandler = new EventBusTransportHandler(() -> List.of(delayedProvider));
        vertx.runOnContext(ignored -> context.verify(() -> {
            StubMessage message = new StubMessage(null);
            message.headers().add(delayedProvider.key(), delayedProvider.marshalToString(
                FlowId.builder().withId("remote").build()));
            StubDeliveryContext delivery = new StubDeliveryContext(message);

            customHandler.handleInbound(delivery);

            assertSame(local, Storage.get(delayedProvider.key()));
            assertEquals(1, delivery.nextCalls);
            context.completeNow();
        }));
    }

    private static final class FailingService extends FlowIdService {
        @Override
        public String marshalRemovalToString() {
            return null;
        }

        @Override
        public String key() {
            return "broken";
        }

        @Override
        public String marshalToString(ContextChainDataItem model) {
            throw new IllegalArgumentException("Serialization failed");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void handle_nextInterceptorThrows_doesNotSwallowApplicationFailure(boolean inbound) {
        IllegalStateException failure = new IllegalStateException("Downstream application failure");
        StubDeliveryContext delivery = new StubDeliveryContext(new StubMessage(null));
        delivery.nextFailure = failure;

        IllegalStateException reported = assertThrows(IllegalStateException.class, () -> {
            if (inbound) {
                handler.handleInbound(delivery);
            } else {
                handler.handleOutbound(delivery);
            }
        });

        assertSame(failure, reported);
        assertEquals(1, delivery.nextCalls);
    }

	private static final class StubDeliveryContext implements DeliveryContext<Object> {
		private final Message<Object> message;
		private boolean nextCalled;
        private int nextCalls;
        private RuntimeException nextFailure;

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
            nextCalls++;
            if (nextFailure != null) {
                throw nextFailure;
            }
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

		@Override
		public boolean fail(int failureCode, String message) {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public boolean fail(ReplyFailure failure, int failureCode, String message) {
			// TODO Auto-generated method stub
			return false;
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
