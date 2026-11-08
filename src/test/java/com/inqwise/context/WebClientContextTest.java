package com.inqwise.context;

import java.util.ArrayList;
import java.util.List;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.inqwise.context.ClientContextTestSupport.assertContext;
import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(10)
class WebClientContextTest {
    @ParameterizedTest
    @ValueSource(ints = {200, 503})
    void send_httpResponse_preservesCallerInstance(int status, Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        vertx.createHttpServer().requestHandler(request -> context.verify(() -> {
            assertNull(Storage.get(ClientContextTestSupport.KEY));
            request.response().setStatusCode(status).end("response");
        })).listen(0, "127.0.0.1").compose(server -> inFlow(vertx, "caller", item ->
            client.get(server.actualPort(), "127.0.0.1", "/").send().map(response -> {
                assertContext(item);
                assertEquals(status, response.statusCode());
                assertEquals("response", response.bodyAsString());
                return response;
            }))).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    @Test
    void send_connectionClosed_preservesInstanceInFailureCallback(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        vertx.createHttpServer().requestHandler(request -> request.connection().close())
            .listen(0, "127.0.0.1").compose(server -> inFlow(vertx, "caller", item ->
                client.get(server.actualPort(), "127.0.0.1", "/").send().transform(result -> {
                    assertTrue(result.failed(), "Expected connection failure");
                    assertContext(item);
                    return Future.succeededFuture();
                }))).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }

    @Test
    void send_sharedClientAndOverlappingCalls_isolatesCallerInstances(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        List<HttpServerRequest> waiting = new ArrayList<>();
        vertx.createHttpServer().requestHandler(request -> {
            waiting.add(request);
            if (waiting.size() == 2) {
                // Hold the first response until both flows have reached the server.
                waiting.get(1).response().end(waiting.get(1).path());
                waiting.get(0).response().end(waiting.get(0).path());
            }
        }).listen(0, "127.0.0.1").compose(server -> {
            List<Future<Void>> flows = new ArrayList<>();
            for (String id : List.of("first", "second")) {
                flows.add(inFlow(vertx, id, item -> client.get(server.actualPort(), "127.0.0.1", "/" + id)
                    .send().map(response -> {
                        assertContext(item);
                        assertEquals("/" + item.id(), response.bodyAsString());
                        return response;
                    })));
            }
            return Future.all(flows);
        }).andThen(ignored -> client.close()).onComplete(context.succeedingThenComplete());
    }
}
