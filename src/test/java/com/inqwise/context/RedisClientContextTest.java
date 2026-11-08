package com.inqwise.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.ProtocolVersion;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisOptions;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import io.vertx.redis.client.impl.ParserHandler;
import io.vertx.redis.client.impl.RESPParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.inqwise.context.ClientContextTestSupport.assertContext;
import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(10)
class RedisClientContextTest {
    @ParameterizedTest
    @ValueSource(strings = {"success", "error", "disconnect"})
    void send_responseOrFailure_preservesCallerInstance(String outcome, Vertx vertx, VertxTestContext context) {
        server(vertx, context, (socket, command) -> {
            switch (outcome) {
                case "success" -> socket.write("+reply\r\n");
                case "error" -> socket.write("-ERR controlled failure\r\n");
                case "disconnect" -> socket.close();
                default -> throw new AssertionError("Unexpected outcome");
            }
        }).compose(server -> {
            Redis client = client(vertx, server.actualPort());
            return inFlow(vertx, "caller", item -> client.send(Request.cmd(Command.ECHO).arg("reply")).transform(result -> {
                assertContext(item);
                assertEquals(!outcome.equals("success"), result.failed());
                if (result.succeeded()) {
                    assertEquals("reply", result.result().toString());
                } else if (outcome.equals("error")) {
                    assertTrue(result.cause().getMessage().contains("controlled failure"));
                }
                return Future.succeededFuture(item);
            })).eventually(client::close);
        }).onComplete(context.succeedingThenComplete());
    }

    @Test
    void send_sharedPoolAndOverlappingCalls_isolatesCallerInstances(Vertx vertx, VertxTestContext context) {
        List<Pending> waiting = new ArrayList<>();
        server(vertx, context, (socket, command) -> {
            assertEquals("ECHO", command.get(0).toString().toUpperCase(Locale.ROOT));
            waiting.add(new Pending(socket, command.get(1).toString()));
            if (waiting.size() == 2) {
                for (Pending pending : waiting) {
                    pending.socket().write("+" + pending.value() + "\r\n");
                }
            }
        }).compose(server -> {
            Redis client = client(vertx, server.actualPort());
            List<Future<Void>> flows = new ArrayList<>();
            for (String id : List.of("first", "second")) {
                flows.add(inFlow(vertx, id, item -> client.send(Request.cmd(Command.ECHO).arg(item.id()))
                    .map(response -> {
                        assertContext(item);
                        assertEquals(item.id(), response.toString());
                        return response;
                    })));
            }
            return Future.all(flows).eventually(client::close);
        }).onComplete(context.succeedingThenComplete());
    }

    private Redis client(Vertx vertx, int port) {
        return Redis.createClient(vertx, new RedisOptions()
            .setConnectionString("redis://127.0.0.1:" + port)
            .setPreferredProtocolVersion(ProtocolVersion.RESP2)
            .setProtocolNegotiation(false)
            .setClientId(false)
            .setMaxPoolSize(2));
    }

    private Future<NetServer> server(Vertx vertx, VertxTestContext context,
            BiConsumer<NetSocket, Response> commandHandler) {
        return vertx.createNetServer().connectHandler(socket -> socket.handler(new RESPParser(new ParserHandler() {
            @Override
            public void handle(Response command) {
                context.verify(() -> {
                    String name = command.get(0).toString().toUpperCase(Locale.ROOT);
                    if (name.equals("PING")) {
                        socket.write("+PONG\r\n");
                    } else if (name.equals("CLIENT") || name.equals("SELECT")) {
                        socket.write("+OK\r\n");
                    } else {
                        commandHandler.accept(socket, command);
                    }
                });
            }

            @Override
            public void fail(Throwable error) {
                context.failNow(error);
            }
        }))).listen(0, "127.0.0.1");
    }

    private record Pending(NetSocket socket, String value) {
    }
}
