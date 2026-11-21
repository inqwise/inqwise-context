package com.inqwise.context;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.inqwise.context.ContextPropagationException.Direction;
import com.inqwise.context.data.FlowId;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.internal.ContextInternal;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@ExtendWith(VertxExtension.class)
@Timeout(15)
class HttpHeaderFailureTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void handler_customResponseHeaderFailure_deliversPayloadAndRecovers(
            boolean existing, Vertx vertx, VertxTestContext context) {
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        vertx.exceptionHandler(failure -> {
            failures.add(failure);
            throw new IllegalStateException("Controlled metadata observer failure");
        });
        WebClient client = WebClient.create(vertx);
        var adapter = HttpContextTransport.handler(request -> context.verify(() -> {
            FlowId outgoing = FlowId.builder().withId("server" + request.path()).build();
            Storage.put("flow_id", outgoing);
            request.response().end(request.path());
            assertSame(outgoing, Storage.get("flow_id"));
        }));
        vertx.createHttpServer().requestHandler(request -> {
            AtomicBoolean fail = new AtomicBoolean(request.path().equals("/invalid"));
            HttpServerResponse response = (HttpServerResponse) Proxy.newProxyInstance(
                HttpServerResponse.class.getClassLoader(), new Class<?>[] {HttpServerResponse.class}, (proxy, method, args) -> {
                    if (method.getName().equals("headers") && fail.getAndSet(false)) {
                        throw new IllegalArgumentException("private-header-accessor-data");
                    }
                    return invoke(request.response(), method, args);
                });
            // A custom request keeps native dispatch affinity while exposing a failing response accessor.
            HttpServerRequest wrapped = (HttpServerRequest) Proxy.newProxyInstance(
                HttpServerRequest.class.getClassLoader(), new Class<?>[] {HttpServerRequest.class}, (proxy, method, args) ->
                    method.getName().equals("response") ? response : invoke(request, method, args));
            adapter.handle(wrapped);
        }).listen(0, "127.0.0.1").compose(server -> {
            HttpContextTransport.install(client, URI.create("http://127.0.0.1:" + server.actualPort()));
            return inFlow(vertx, "caller", ignored -> {
                ContextInternal caller = ContextInternal.current();
                FlowId original = FlowId.builder().withId("caller").build();
                if (existing) {
                    Storage.put("flow_id", original);
                }
                return client.get(server.actualPort(), "127.0.0.1", "/invalid").send().compose(response -> {
                    assertEquals(200, response.statusCode());
                    assertEquals("/invalid", response.bodyAsString());
                    assertFalse(HttpContextCarrier.hasMetadata(response.headers()));
                    assertSame(caller, ContextInternal.current());
                    assertSame(existing ? original : null, Storage.get("flow_id"));
                    assertEquals(1, failures.size());
                    var failure = assertInstanceOf(ContextPropagationException.class, failures.getFirst());
                    assertEquals(Direction.OUTBOUND, failure.getDirection());
                    assertNull(failure.getProviderClassName());
                    assertNull(failure.getCause());
                    assertFalse(failure.getMessage().contains("private-header-accessor-data"));
                    return client.get(server.actualPort(), "127.0.0.1", "/valid").send();
                }).compose(response -> {
                    assertEquals("/valid", response.bodyAsString());
                    assertEquals("no-store", response.getHeader("Cache-Control"));
                    assertSame(caller, ContextInternal.current());
                    FlowId current = (FlowId) Storage.get("flow_id");
                    if (existing) {
                        assertSame(original, current);
                    } else {
                        assertEquals("server/valid", current.getId());
                    }
                    assertEquals(1, failures.size());
                    return vertx.timer(1).map(tick -> {
                        assertSame(caller, ContextInternal.current());
                        assertSame(current, Storage.get("flow_id"));
                        return tick;
                    });
                });
            });
        }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    private static Object invoke(Object delegate, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
