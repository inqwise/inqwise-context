package com.inqwise.context;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.internal.ContextInternal;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class HttpRedirectContextTransportTest {
    @ParameterizedTest
    @MethodSource("redirectModes")
    void redirects_eachHopUsesCallerStateAndOnlyFinalResponseIsImported(String localState,
            boolean middleAllowed, boolean targetAllowed, boolean remoteRemoval,
            Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient client = WebClient.create(vertx);
        FlowIdService codec = new FlowIdService();
        FlowId original = FlowId.builder().withId("caller").build();
        String finalValue = remoteRemoval ? codec.marshalRemovalToString()
            : codec.marshalToString(FlowId.builder().withId("final-response").build());
        List<String> visited = new ArrayList<>();
        vertx.createHttpServer().requestHandler(request -> context.verify(() -> {
            visited.add("target");
            assertRequestMetadata(request, localState, targetAllowed, original);
            new HttpContextCarrier(request.response().headers()).set("flow_id", finalValue);
            request.response().end("delivered");
        })).listen(0, "127.0.0.1").compose(target -> vertx.createHttpServer()
            .requestHandler(request -> context.verify(() -> {
                visited.add("middle");
                assertRequestMetadata(request, localState, middleAllowed, original);
                new HttpContextCarrier(request.response().headers()).set("flow_id",
                    codec.marshalToString(FlowId.builder().withId("intermediate-response").build()));
                request.response().setStatusCode(307).putHeader("Location", origin(target.actualPort()) + "/target").end();
            })).listen(0, "127.0.0.1").compose(middle -> vertx.createHttpServer()
                .requestHandler(request -> context.verify(() -> {
                    visited.add("source");
                    assertRequestMetadata(request, localState, true, original);
                    // Followed responses must not even decode optional metadata.
                    request.response().putHeader(HttpContextCarrier.name("flow_id"), "1:%%%");
                    request.response().setStatusCode(302).putHeader("Location", origin(middle.actualPort()) + "/middle").end();
                })).listen(0, "127.0.0.1").compose(source -> {
                    List<URI> origins = new ArrayList<>();
                    origins.add(origin(source.actualPort()));
                    if (middleAllowed) {
                        origins.add(origin(middle.actualPort()));
                    }
                    if (targetAllowed) {
                        origins.add(origin(target.actualPort()));
                    }
                    HttpContextTransport.install(client, origins.toArray(URI[]::new));
                    return inFlow(vertx, localState, ignored -> {
                        ContextInternal caller = ContextInternal.current();
                        if (localState.equals("value")) {
                            Storage.put("flow_id", original);
                        } else if (localState.equals("removed")) {
                            Storage.remove("flow_id");
                        }
                        return client.get(source.actualPort(), "127.0.0.1", "/source")
                            .putHeader(HttpContextCarrier.name("flow_id"), "stale-managed-header").send().map(response -> {
                                assertEquals("delivered", response.bodyAsString());
                                assertEquals(List.of("source", "middle", "target"), visited);
                                assertEquals(2, response.followedRedirects().size());
                                assertSame(caller, ContextInternal.current());
                                if (localState.equals("value")) {
                                    assertSame(original, Storage.get("flow_id"));
                                } else if (localState.equals("empty") && targetAllowed && !remoteRemoval) {
                                    assertEquals("final-response", ((FlowId) Storage.get("flow_id")).getId());
                                } else {
                                    assertNull(Storage.get("flow_id"));
                                }
                                MultiMap exported = MultiMap.caseInsensitiveMultiMap();
                                ContextTransport.writeTo(new HttpContextCarrier(exported));
                                if (localState.equals("removed") || (localState.equals("empty") && targetAllowed && remoteRemoval)) {
                                    assertTrue(codec.isRemoval(new HttpContextCarrier(exported).getAll("flow_id").getFirst()));
                                } else if (localState.equals("empty") && !targetAllowed) {
                                    assertFalse(HttpContextCarrier.hasMetadata(exported));
                                }
                                return response;
                            });
                    });
                }))).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    private static void assertRequestMetadata(HttpServerRequest request, String localState, boolean allowed, FlowId original) {
        HttpContextCarrier carrier = new HttpContextCarrier(request.headers());
        if (!allowed || localState.equals("empty")) {
            assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
        } else {
            List<String> values = carrier.getAll("flow_id");
            assertEquals(1, values.size());
            FlowIdService codec = new FlowIdService();
            if (localState.equals("removed")) {
                assertTrue(codec.isRemoval(values.getFirst()));
            } else {
                assertEquals(original.toJson(), ((FlowId) codec.unmarshalFormString(values.getFirst())).toJson());
            }
        }
    }

    private static Stream<Arguments> redirectModes() {
        return Stream.of("value", "removed", "empty").flatMap(state -> Stream.of(false, true)
            .flatMap(middle -> Stream.of(false, true).flatMap(target -> Stream.of(false, true)
                .map(removal -> Arguments.of(state, middle, target, removal)))));
    }

    private static URI origin(int port) {
        return URI.create("http://127.0.0.1:" + port);
    }
}
