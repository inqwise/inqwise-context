package com.inqwise.context;

import java.net.URI;
import java.util.stream.Stream;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.client.HttpRequest;
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
class HttpRoutingPolicyTest {
    private enum Routing {
        SOCKET_MATCHING, SOCKET_OVERRIDE, HOST_OVERRIDE, PORT_OVERRIDE, MATCHING_AUTHORITY
    }

    @ParameterizedTest
    @MethodSource("routingModes")
    void client_routingOverrides_enforcePolicyWithoutBlockingPayload(
            Routing routing, String localState, boolean remoteRemoval,
            Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        WebClient client = WebClient.create(vertx);
        FlowIdService codec = new FlowIdService();
        FlowId original = FlowId.builder().withId("caller").build();
        FlowId privateValue = FlowId.builder().withId("private").build();
        boolean allowed = routing == Routing.MATCHING_AUTHORITY;
        vertx.createHttpServer().requestHandler(request -> context.verify(() -> {
            URI authority = URI.create("http://" + request.getHeader("Host"));
            URI expected = expectedAuthority(routing, request.localAddress().port());
            assertEquals(expected.getHost(), authority.getHost());
            assertEquals(port(expected), port(authority));
            assertEquals("retained", request.getHeader("application"));
            assertNull(request.getHeader(HttpContextCarrier.name("private")));
            HttpContextCarrier carrier = new HttpContextCarrier(request.headers());
            if (!allowed || localState.equals("empty")) {
                assertFalse(HttpContextCarrier.hasMetadata(request.headers()));
            } else if (localState.equals("removed")) {
                assertTrue(codec.isRemoval(carrier.getAll("flow_id").getFirst()));
            } else {
                assertEquals(original.toJson(), ((FlowId) codec.unmarshalFormString(carrier.getAll("flow_id").getFirst())).toJson());
            }
            new HttpContextCarrier(request.response().headers()).set("flow_id", remoteRemoval
                ? codec.marshalRemovalToString()
                : codec.marshalToString(FlowId.builder().withId("server-response").build()));
            request.response().end("delivered");
        })).listen(0, "127.0.0.1").compose(server -> {
            int port = server.actualPort();
            // Allow both claimed and physical origins, so mismatches must be rejected independently.
            HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + port),
                URI.create("http://trusted.invalid"), URI.create("http://spoofed.invalid:" + port),
                URI.create("http://127.0.0.1:1"));
            return inFlow(vertx, localState, ignored -> {
                if (localState.equals("value")) {
                    Storage.put("flow_id", original);
                } else if (localState.equals("removed")) {
                    Storage.remove("flow_id");
                }
                Storage.put("private", privateValue);
                HttpRequest<Buffer> request = request(client, routing, port);
                request.putHeader(HttpContextCarrier.name("flow_id"), "stale-managed-header")
                    .putHeader(HttpContextCarrier.name("private"), "stale-private-header")
                    .putHeader("application", "retained");
                return request.timeout(3_000).send().map(response -> {
                    assertEquals("delivered", response.bodyAsString());
                    assertTrue(HttpContextCarrier.hasMetadata(response.headers()));
                    assertSame(privateValue, Storage.get("private"));
                    if (localState.equals("value")) {
                        assertSame(original, Storage.get("flow_id"));
                    } else if (localState.equals("empty") && allowed && !remoteRemoval) {
                        assertEquals("server-response", ((FlowId) Storage.get("flow_id")).getId());
                    } else {
                        assertNull(Storage.get("flow_id"));
                    }
                    MultiMap exported = MultiMap.caseInsensitiveMultiMap();
                    ContextTransport.writeTo(new HttpContextCarrier(exported));
                    boolean removed = localState.equals("removed")
                        || (localState.equals("empty") && allowed && remoteRemoval);
                    if (removed) {
                        assertTrue(codec.isRemoval(new HttpContextCarrier(exported).getAll("flow_id").getFirst()));
                    } else if (localState.equals("empty") && !allowed) {
                        assertFalse(HttpContextCarrier.hasMetadata(exported));
                    }
                    return response;
                });
            });
        }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    private static HttpRequest<Buffer> request(WebClient client, Routing routing, int port) {
        if (routing == Routing.SOCKET_MATCHING || routing == Routing.SOCKET_OVERRIDE) {
            boolean matching = routing == Routing.SOCKET_MATCHING;
            return client.request(new RequestOptions().setMethod(HttpMethod.GET).setURI("/")
                .setHost(matching ? "127.0.0.1" : "trusted.invalid").setPort(matching ? port : 80)
                .setServer(SocketAddress.inetSocketAddress(port, "127.0.0.1")));
        }
        URI authority = expectedAuthority(routing, port);
        return client.get(port, "127.0.0.1", "/").putHeader("Host", authority.getRawAuthority());
    }

    private static URI expectedAuthority(Routing routing, int port) {
        return switch (routing) {
            case SOCKET_OVERRIDE -> URI.create("http://trusted.invalid");
            case HOST_OVERRIDE -> URI.create("http://spoofed.invalid:" + port);
            case PORT_OVERRIDE -> URI.create("http://127.0.0.1:1");
            default -> URI.create("http://127.0.0.1:" + port);
        };
    }

    private static int port(URI uri) {
        return uri.getPort() == -1 ? 80 : uri.getPort();
    }

    private static Stream<Arguments> routingModes() {
        return Stream.of(Routing.values()).flatMap(routing -> Stream.of("empty", "value", "removed")
            .flatMap(state -> Stream.of(false, true).map(removal -> Arguments.of(routing, state, removal))));
    }
}
