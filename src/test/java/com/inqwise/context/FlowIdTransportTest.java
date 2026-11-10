package com.inqwise.context;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.data.FlowIdService.CodecProfile;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

@ExtendWith(VertxExtension.class)
@Timeout(10)
class FlowIdTransportTest {
    private enum ReturnAction {
        REPLY, REPLACE_AND_REPLY, REPLACE_AND_FAIL
    }

    @ParameterizedTest
    @MethodSource("transports")
    void request_allProfilesAndReturnActions_preserveOriginatingFlowId(
            CodecProfile profile, ReturnAction action, Vertx vertx, VertxTestContext context) {
        FlowIdService codec = new FlowIdService(profile);
        EventBusTransportHandler writer = new EventBusTransportHandler(() -> List.of(codec));
        vertx.eventBus().addOutboundInterceptor(writer::handleOutbound);
        FlowId original = FlowId.builder().withId("origin")
            .withTimestamp(Instant.parse("2026-11-08T10:00:00.123Z")).build();
        FlowId replacement = FlowId.builder().withId("remote-replacement").build();
        var consumer = vertx.eventBus().<String>consumer("flow-id-test", message -> context.verify(() -> {
            FlowId received = (FlowId) Storage.get(codec.key());
            assertNotSame(original, received);
            assertEquals(original.getId(), received.getId());
            assertEquals(original.getTimestamp(), received.getTimestamp());
            assertEquals(codec.marshalToString(original), message.headers().get(codec.key()));
            if (action != ReturnAction.REPLY) {
                Storage.put(codec.key(), replacement);
                assertSame(replacement, Storage.get(codec.key()));
            }
            if (action == ReturnAction.REPLACE_AND_FAIL) {
                message.fail(37, "controlled failure");
            } else {
                message.reply("done");
            }
        }));

        consumer.completion().compose(ignored -> inFlow(vertx, "caller", item -> {
            Storage.put(codec.key(), original);
            return vertx.eventBus().<String>request("flow-id-test", "request").transform(result -> {
                assertSame(original, Storage.get(codec.key()));
                assertEquals(action == ReturnAction.REPLACE_AND_FAIL, result.failed());
                if (result.failed()) {
                    assertEquals(37, assertInstanceOf(ReplyException.class, result.cause()).failureCode());
                } else {
                    assertEquals("done", result.result().body());
                    FlowId returned = (FlowId) codec.unmarshalFormString(result.result().headers().get(codec.key()));
                    assertEquals(action == ReturnAction.REPLY ? original.getId() : replacement.getId(), returned.getId());
                }
                return vertx.timer(1).map(fired -> {
                    assertSame(original, Storage.get(codec.key()));
                    return original;
                });
            });
        })).onComplete(context.succeedingThenComplete());
    }

    private static Stream<Arguments> transports() {
        return Arrays.stream(CodecProfile.values()).flatMap(profile -> Arrays.stream(ReturnAction.values())
            .map(action -> Arguments.of(profile, action)));
    }
}
