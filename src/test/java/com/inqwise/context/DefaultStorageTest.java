package com.inqwise.context;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.inqwise.context.data.FlowId;

import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

@ExtendWith(VertxExtension.class)
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
                    assertNull(Storage.get("missing"));
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
	void put_outsideVertxContext_throwsIllegalStateException() {
		FlowId value = FlowId.builder()
			.withId("outside")
			.build();

		assertThrows(IllegalStateException.class, () -> Storage.put(FLOW_ID_KEY, value));
	}

    @Test
    void get_outsideVertxContext_throwsIllegalStateException() {
        assertThrows(IllegalStateException.class, () -> Storage.get(FLOW_ID_KEY));
    }

    @Test
    void putIfAbsent_outsideVertxContext_throwsIllegalStateException() {
        FlowId value = FlowId.builder().withId("outside").build();
        assertThrows(IllegalStateException.class, () -> DefaultStorage.put(FLOW_ID_KEY, value, false));
    }

    @Test
    void explicitContextAccess_withoutThreadContext_usesCapturedStorage(Vertx vertx) {
        ContextInternal captured = (ContextInternal) vertx.getOrCreateContext();
        FlowId original = FlowId.builder().withId("captured").build();
        FlowId replacement = FlowId.builder().withId("replacement").build();
        assertNull(Vertx.currentContext());

        DefaultStorage.put(captured, FLOW_ID_KEY, original, false);
        DefaultStorage.put(captured, FLOW_ID_KEY, replacement, false);
        assertSame(original, DefaultStorage.get(captured, FLOW_ID_KEY));
        DefaultStorage.put(captured, FLOW_ID_KEY, replacement, true);
        assertSame(replacement, DefaultStorage.get(captured, FLOW_ID_KEY));
        assertThrows(IllegalStateException.class, () -> Storage.get(FLOW_ID_KEY));
    }

    @Test
    void remove_existingItem_returnsInstanceAndAllowsExplicitReaddition(Vertx vertx, VertxTestContext context) {
        vertx.runOnContext(ignored -> context.verify(() -> {
            FlowId original = FlowId.builder().withId("original").build();
            FlowId replacement = FlowId.builder().withId("replacement").build();
            Storage.put(FLOW_ID_KEY, original);

            assertSame(original, Storage.remove(FLOW_ID_KEY));
            assertNull(Storage.get(FLOW_ID_KEY));
            assertNull(Storage.remove(FLOW_ID_KEY));
            Storage.put(FLOW_ID_KEY, replacement);
            assertSame(replacement, Storage.get(FLOW_ID_KEY));
            context.completeNow();
        }));
    }

    @Test
    void remove_absentItem_blocksImplicitImportWithoutAffectingOtherKeys(Vertx vertx, VertxTestContext context) {
        vertx.runOnContext(ignored -> context.verify(() -> {
            FlowId other = FlowId.builder().withId("other").build();
            Storage.put("other", other);

            assertNull(Storage.remove(FLOW_ID_KEY));
            DefaultStorage.put(ContextInternal.current(), FLOW_ID_KEY, FlowId.builder().withId("remote").build(), false);
            assertNull(Storage.get(FLOW_ID_KEY));
            assertSame(other, Storage.get("other"));
            context.completeNow();
        }));
    }

    @Test
    void remove_outsideVertxContext_throwsIllegalStateException() {
        assertThrows(IllegalStateException.class, () -> Storage.remove(FLOW_ID_KEY));
    }

    @Test
    void remove_nullKey_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> Storage.remove(null));
    }
}
