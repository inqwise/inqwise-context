package com.inqwise.context;

import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.inqwise.context.ContextPropagationException.Direction;
import com.inqwise.context.data.FlowId;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class HttpResponseMetadataFailureTest {
    @ParameterizedTest
    @MethodSource("failureModes")
    void response_oversizedMetadataAndFailingObserver_deliverBodyAndRecoverOnNextRequest(
            ThreadingModel model, boolean observerThrows, String state, Vertx vertx, VertxTestContext testContext) {
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        vertx.exceptionHandler(error -> {
            failures.add(error);
            if (observerThrows) {
                throw new IllegalStateException("Controlled metadata observer failure");
            }
        });
        WebClient client = WebClient.create(vertx);
        FlowId original = FlowId.builder().withId("caller").build();
        byte[] payload = new byte[4096];
        new Random(0).nextBytes(payload);
        FlowId oversized = FlowId.builder().withId("private-oversized-" + Base64.getEncoder().encodeToString(payload)).build();
        FlowId valid = FlowId.builder().withId("recovered").build();
        vertx.createHttpServer().requestHandler(HttpContextTransport.handler(request -> testContext.verify(() -> {
            ContextInternal serverScope = ContextInternal.current();
            FlowId outgoing = request.path().equals("/oversized") ? oversized : valid;
            vertx.timer(1).onComplete(tick -> testContext.verify(() -> {
                assertTrue(tick.succeeded());
                assertSame(serverScope, ContextInternal.current());
                Storage.put("flow_id", outgoing);
                request.response().putHeader(HttpContextCarrier.name("flow_id"), "stale-managed-header")
                    .putHeader(HttpContextCarrier.name("private"), "stale-private-header")
                    .putHeader("application", "retained").end(request.path());
                assertSame(outgoing, Storage.get("flow_id"));
            }));
        }))).listen(0, "127.0.0.1").compose(server -> {
            HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + server.actualPort()));
            return vertx.deployVerticle(new VerticleBase() {
                @Override
                public Future<?> start() {
                    ContextInternal caller = ContextInternal.current();
                    if (state.equals("value")) {
                        Storage.put("flow_id", original);
                    } else if (state.equals("removed")) {
                        Storage.remove("flow_id");
                    }
                    return client.get(server.actualPort(), "127.0.0.1", "/oversized").send().compose(response -> {
                        assertSame(caller, ContextInternal.current());
                        assertEquals(model, caller.threadingModel());
                        assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                        assertEquals(200, response.statusCode());
                        assertEquals("/oversized", response.bodyAsString());
                        assertEquals("retained", response.getHeader("application"));
                        assertFalse(HttpContextCarrier.hasMetadata(response.headers()));
                        assertNull(response.getHeader("Cache-Control"));
                        assertSame(state.equals("value") ? original : null, Storage.get("flow_id"));
                        assertEquals(state.equals("removed"), DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                        assertEquals(1, failures.size());
                        ContextPropagationException failure = assertInstanceOf(ContextPropagationException.class, failures.getFirst());
                        assertEquals(Direction.OUTBOUND, failure.getDirection());
                        assertFalse(failure.getMessage().contains("private-oversized-"));
                        assertNull(failure.getCause());
                        return client.get(server.actualPort(), "127.0.0.1", "/valid").send();
                    }).compose(response -> {
                        assertSame(caller, ContextInternal.current());
                        assertEquals("/valid", response.bodyAsString());
                        assertEquals("no-store", response.getHeader("Cache-Control"));
                        assertNull(response.getHeader(HttpContextCarrier.name("private")));
                        assertEquals(1, failures.size());
                        if (state.equals("empty")) {
                            assertEquals(valid.toJson(), ((FlowId) Storage.get("flow_id")).toJson());
                        } else {
                            assertSame(state.equals("value") ? original : null, Storage.get("flow_id"));
                        }
                        assertEquals(state.equals("removed"), DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                        var current = Storage.get("flow_id");
                        return vertx.timer(1).map(tick -> {
                            assertSame(caller, ContextInternal.current());
                            assertSame(current, Storage.get("flow_id"));
                            assertEquals(state.equals("removed"), DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                            return tick;
                        });
                    });
                }
            }, new DeploymentOptions().setThreadingModel(model));
        }).andThen(ignored -> client.close()).onComplete(testContext.succeedingThenComplete());
    }

    private static Stream<Arguments> failureModes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of(false, true).flatMap(observer -> Stream.of("value", "removed", "empty")
                .map(state -> Arguments.of(model, observer, state))));
    }
}
