package com.inqwise.context;

import java.time.Instant;

import com.inqwise.context.data.FlowId;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@ExtendWith(VertxExtension.class)
@Timeout(10)
class StorageDeploymentTest {
    @ParameterizedTest
    @EnumSource(value = ThreadingModel.class, names = {"EVENT_LOOP", "WORKER"})
    void deployedVerticles_callAndReturn_accessStorageWithoutContextWrappers(
            ThreadingModel model, Vertx vertx, VertxTestContext testContext) {
        FlowId original = FlowId.builder().withId("deployed-flow")
            .withTimestamp(Instant.parse("2026-11-08T10:00:00Z")).build();
        VerticleBase receiver = new VerticleBase() {
            @Override
            public Future<?> start() {
                assertNull(Storage.get("flow_id"));
                return vertx.eventBus().<String>consumer("deployed-receiver", message -> testContext.verify(() -> {
                    FlowId received = (FlowId) Storage.get("flow_id");
                    assertNotSame(original, received);
                    assertEquals(original.getId(), received.getId());
                    assertEquals(original.getTimestamp(), received.getTimestamp());
                    message.reply("done");
                })).completion();
            }
        };
        VerticleBase sender = new VerticleBase() {
            @Override
            public Future<?> start() {
                Storage.put("flow_id", original);
                assertSame(original, Storage.get("flow_id"));
                return vertx.eventBus().<String>request("deployed-receiver", "request").map(reply -> {
                    assertEquals("done", reply.body());
                    assertSame(original, Storage.get("flow_id"));
                    return reply;
                });
            }
        };

        vertx.deployVerticle(receiver, new DeploymentOptions().setThreadingModel(model))
            .compose(ignored -> vertx.deployVerticle(sender, new DeploymentOptions().setThreadingModel(model)))
            .onComplete(testContext.succeedingThenComplete());
    }
}
