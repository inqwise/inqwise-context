package com.inqwise.context;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.SimpleJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.Endpoint;
import co.elastic.clients.transport.TransportOptions;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.inqwise.context.ClientContextTestSupport.assertContext;
import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(VertxExtension.class)
@Timeout(10)
class ElasticsearchClientContextTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ping_explicitContextAdapter_preservesInstanceOnSuccessAndFailure(
            boolean fail, Vertx vertx, VertxTestContext context) {
        inFlow(vertx, "caller", item -> {
            ControlledTransport transport = new ControlledTransport(item);
            ElasticsearchAsyncClient client = new ElasticsearchAsyncClient(transport);
            IOException failure = new IOException("controlled failure");
            CompletableFuture<BooleanResponse> stage = client.ping();
            assertContext(item);

            // Bind the non-Vert.x CompletionStage before registering application callbacks.
            Future<BooleanResponse> adapted = ContextPropagation.bind(stage);
            CompletableFuture.runAsync(() -> {
                try {
                    assertNull(Vertx.currentContext());
                    assertThrows(IllegalStateException.class, () -> Storage.get(ClientContextTestSupport.KEY));
                    if (fail) {
                        transport.response.completeExceptionally(failure);
                    } else {
                        transport.response.complete(new BooleanResponse(true));
                    }
                } catch (Throwable error) {
                    transport.response.completeExceptionally(error);
                }
            });

            return adapted.transform(result -> {
                assertContext(item);
                assertEquals(fail, result.failed());
                if (fail) {
                    assertSame(failure, result.cause());
                } else {
                    assertTrue(result.result().value());
                }
                return Future.succeededFuture(item);
            });
        }).onComplete(context.succeedingThenComplete());
    }

    private static final class ControlledTransport implements ElasticsearchTransport {
        private final ClientContextTestSupport.Item expected;
        private final CompletableFuture<BooleanResponse> response = new CompletableFuture<>();

        private ControlledTransport(ClientContextTestSupport.Item expected) {
            this.expected = expected;
        }

        @Override
        public <RequestT, ResponseT, ErrorT> ResponseT performRequest(RequestT request,
                Endpoint<RequestT, ResponseT, ErrorT> endpoint, TransportOptions options) {
            throw new UnsupportedOperationException("Only asynchronous requests are tested");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <RequestT, ResponseT, ErrorT> CompletableFuture<ResponseT> performRequestAsync(RequestT request,
                Endpoint<RequestT, ResponseT, ErrorT> endpoint, TransportOptions options) {
            assertContext(expected);
            assertEquals("es/ping", endpoint.id());
            return (CompletableFuture<ResponseT>) (CompletableFuture<?>) response;
        }

        @Override
        public JsonpMapper jsonpMapper() {
            return new SimpleJsonpMapper();
        }

        @Override
        public TransportOptions options() {
            return null;
        }

        @Override
        public void close() {
        }
    }
}
