package com.inqwise.context;

import java.net.URI;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.spi.ContextChainDataItem;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.internal.ContextInternal;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.impl.ClientPhase;
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
class HttpTimeoutContextTest {
    @ParameterizedTest
    @MethodSource("timeoutModes")
    void response_idleTimeout_preservesStateAccordingToReceivedHeaders(
            ThreadingModel model, String localState, boolean headersFirst, boolean remoteRemoval,
            Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient client = WebClient.create(vertx);
        Promise<Void> requestSeen = Promise.promise();
        Promise<Void> headersSeen = Promise.promise();
        Promise<HttpClientRequest> nativeRequest = Promise.promise();
        vertx.createHttpServer().requestHandler(HttpContextTransport.handler(request -> {
            request.response().exceptionHandler(ignored -> { });
            if (remoteRemoval) {
                Storage.remove("flow_id");
            } else {
                Storage.put("flow_id", FlowId.builder().withId("server-before-timeout").build());
            }
            requestSeen.complete();
            if (headersFirst) {
                request.response().setChunked(true).write("unfinished-body");
            }
        })).listen(0, "127.0.0.1").compose(server -> {
            HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + server.actualPort()));
            client.addInterceptor(event -> {
                if (event.phase() == ClientPhase.SEND_REQUEST) {
                    nativeRequest.tryComplete(event.clientRequest());
                } else if (event.phase() == ClientPhase.RECEIVE_RESPONSE) {
                    headersSeen.tryComplete();
                }
                event.next();
            });
            return vertx.deployVerticle(new VerticleBase() {
                @Override
                public Future<?> start() {
                    ContextInternal caller = ContextInternal.current();
                    assertEquals(model, caller.threadingModel());
                    assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                    FlowId original = FlowId.builder().withId("caller").build();
                    if (!localState.equals("empty")) {
                        Storage.put("flow_id", original);
                    }
                    if (localState.equals("removed")) {
                        Storage.remove("flow_id");
                    }
                    Future<HttpResponse<Buffer>> pending = client.get(server.actualPort(), "127.0.0.1", "/").send();
                    Future<?> ready = Future.join(requestSeen.future(), nativeRequest.future(),
                        headersFirst ? headersSeen.future() : Future.succeededFuture());
                    return ContextPropagation.bind(ready).compose(ignored -> {
                        assertSame(caller, ContextInternal.current());
                        assertFalse(pending.isComplete());
                        // Arm only after the intended boundary, without racing server startup or header delivery.
                        nativeRequest.future().result().idleTimeout(25);
                        return pending.transform(result -> {
                            assertTrue(result.failed());
                            assertInstanceOf(TimeoutException.class, result.cause());
                            assertSame(caller, ContextInternal.current());
                            assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                            assertEquals(headersFirst, headersSeen.future().isComplete());
                            boolean removed = localState.equals("removed")
                                || (localState.equals("empty") && headersFirst && remoteRemoval);
                            if (localState.equals("value")) {
                                assertSame(original, Storage.get("flow_id"));
                            } else if (localState.equals("empty") && headersFirst && !remoteRemoval) {
                                assertEquals("server-before-timeout", ((FlowId) Storage.get("flow_id")).getId());
                            } else {
                                assertNull(Storage.get("flow_id"));
                            }
                            assertEquals(removed, DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                            ContextChainDataItem retained = Storage.get("flow_id");
                            return vertx.timer(1).map(tick -> {
                                assertSame(caller, ContextInternal.current());
                                assertEquals(model == ThreadingModel.VIRTUAL_THREAD, Thread.currentThread().isVirtual());
                                assertSame(retained, Storage.get("flow_id"));
                                assertEquals(removed, DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "flow_id")));
                                return tick;
                            });
                        });
                    });
                }
            }, new DeploymentOptions().setThreadingModel(model));
        }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    private static Stream<Arguments> timeoutModes() {
        return Stream.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)
            .flatMap(model -> Stream.of("empty", "value", "removed")
                .flatMap(local -> Stream.of(false, true).flatMap(headers -> Stream.of(false, true)
                    .map(remoteRemoval -> Arguments.of(model, local, headers, remoteRemoval)))));
    }
}
