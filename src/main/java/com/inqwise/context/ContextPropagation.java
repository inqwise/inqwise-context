package com.inqwise.context;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;

/**
 * Binds asynchronous results and handlers to the calling Vert.x context before
 * they are used by clients. Metadata remains in that context, retaining its
 * object identity; it is neither copied nor sent to a remote service.
 */
public final class ContextPropagation {
    private ContextPropagation() {
    }

    /**
     * Adapts a result that may complete on another context or a foreign thread.
     * Call this method within the originating context and attach continuations
     * to the returned future rather than to the original stage.
     *
     * @param stage the operation's asynchronous result
     * @param <T> the result type
     * @return a future whose callbacks run on the calling context
     * @throws NullPointerException if the stage is null
     * @throws IllegalStateException if called outside a Vert.x context
     */
    public static <T> Future<T> bind(CompletionStage<T> stage) {
        Objects.requireNonNull(stage, "stage is mandatory");
        Context context = Vertx.currentContext();
        if (context == null) {
            throw new IllegalStateException("Context propagation requires a calling Vert.x context");
        }
        return Future.fromCompletionStage(stage, context);
    }

    /**
     * Rebinds a Vert.x future, for example one returned by a shared client that
     * completes results on the client's creation context.
     *
     * @param future the operation's asynchronous result
     * @param <T> the result type
     * @return a future whose callbacks run on the calling context
     * @throws NullPointerException if the future is null
     * @throws IllegalStateException if called outside a Vert.x context
     */
    public static <T> Future<T> bind(Future<T> future) {
        Objects.requireNonNull(future, "future is mandatory");
        return bind(future.toCompletionStage());
    }

    /**
     * Captures the current context for callbacks such as periodic timer ticks.
     * The handler runs immediately on the captured executor or is scheduled on
     * it when called from another thread. The dispatcher's context is restored
     * after the handler returns; exceptions go to the captured context's
     * exception handler.
     *
     * @param handler the callback to bind before registration
     * @param <T> the event type
     * @return a handler that dispatches events on the calling context
     * @throws NullPointerException if the handler is null
     * @throws IllegalStateException if called outside a Vert.x context
     */
    public static <T> Handler<T> bindHandler(Handler<T> handler) {
        Objects.requireNonNull(handler, "handler is mandatory");
        ContextInternal context = ContextInternal.current();
        if (context == null) {
            throw new IllegalStateException("Context propagation requires a calling Vert.x context");
        }
        return event -> context.emit(event, handler);
    }
}
