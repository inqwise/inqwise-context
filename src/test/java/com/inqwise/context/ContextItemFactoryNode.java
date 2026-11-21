package com.inqwise.context;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.inqwise.context.data.FlowId;
import com.inqwise.context.spi.ContextItemFactory;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;

import static com.inqwise.context.TransportClasspathTestSupport.check;

/** Exercises public factory access in a core-only runtime with real SPI registration. */
public final class ContextItemFactoryNode {
    public static void main(String[] arguments) throws Exception {
        boolean invalid = arguments[0].equals("invalid");
        Vertx vertx = Vertx.vertx();
        AtomicReference<IllegalStateException> cachedFailure = new AtomicReference<>();
        try {
            vertx.deployVerticle(new VerticleBase() {
                @Override
                public Future<?> start() {
                    check(FlowIdContext.get() == null, "Read must not create");
                    FlowId explicit = FlowId.builder().withId("explicit").build();
                    check(FlowIdContext.getOrCreate(() -> explicit) == explicit, "Explicit initialization failed");
                    check(FlowIdContext.getOrCreate() == explicit, "Existing value must bypass SPI");
                    check(Factory.constructed.get() == 0 && InvalidFactory.constructed.get() == 0, "SPI was resolved too early");
                    return Future.succeededFuture();
                }
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var consumer = vertx.eventBus().<String>consumer("factory", message -> {
                check(FlowIdContext.require().getId().equals(message.body()), "Inbound identity lost");
                int calls = Factory.created.get();
                check(FlowIdContext.getOrCreate() == FlowIdContext.require(), "Imported identity must bypass creation");
                check(calls == Factory.created.get(), "Factory invoked during inbound read");
                FlowIdContext.put(FlowId.builder().withId("remote-change").build());
                message.reply("delivered");
            });
            consumer.completion().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            for (ThreadingModel model : List.of(ThreadingModel.EVENT_LOOP, ThreadingModel.WORKER, ThreadingModel.VIRTUAL_THREAD)) {
                vertx.deployVerticle(new VerticleBase() {
                    @Override
                    public Future<?> start() {
                        ContextInternal caller = ContextInternal.current();
                        check(caller.threadingModel() == model, "Wrong threading model");
                        FlowId identity;
                        if (invalid) {
                            try {
                                FlowIdContext.getOrCreate();
                                throw new AssertionError("Invalid SPI must reject creation");
                            } catch (IllegalStateException failure) {
                                check(failure.getCause() == null && !failure.getMessage().contains("private-factory"), "Unsanitized SPI error");
                                cachedFailure.compareAndSet(null, failure);
                                check(cachedFailure.get() == failure, "SPI failure was not cached");
                            }
                            check(FlowIdContext.get() == null, "Failed creation changed storage");
                            identity = FlowIdContext.getOrCreate(() -> FlowId.builder().withId("bypass").build());
                        } else {
                            identity = FlowIdContext.getOrCreate();
                            check(identity.getId().startsWith("environment-"), "SPI did not override default factory");
                            check(FlowIdContext.getOrCreate() == identity, "Identity recreated");
                        }
                        FlowId original = identity;
                        return vertx.eventBus().<String>request("factory", identity.getId()).compose(reply -> {
                            check(reply.body().equals("delivered"), "Payload lost");
                            check(ContextInternal.current() == caller && FlowIdContext.require() == original, "Reply changed caller identity");
                            return vertx.timer(1);
                        }).map(tick -> {
                            check(ContextInternal.current() == caller && FlowIdContext.require() == original, "Timer changed caller identity");
                            check(FlowIdContext.remove() == original, "Removal lost previous instance");
                            try {
                                FlowIdContext.getOrCreate();
                                throw new AssertionError("Creation revived removal");
                            } catch (IllegalStateException expected) {
                                check(FlowIdContext.get() == null, "Removal disappeared");
                            }
                            FlowIdContext.put(original);
                            check(FlowIdContext.getOrCreate() == original, "Explicit re-addition failed");
                            return tick;
                        });
                    }
                }, new DeploymentOptions().setThreadingModel(model)).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            check(invalid ? InvalidFactory.constructed.get() == 1
                : Factory.constructed.get() == 1 && Factory.created.get() == 3, "Unexpected factory lifecycle");
            consumer.unregister().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            System.out.println("ITEM_FACTORY_OK");
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /** Application-owned construction strategy. */
    public static final class Factory implements ContextItemFactory<FlowId> {
        static final AtomicInteger constructed = new AtomicInteger();
        static final AtomicInteger created = new AtomicInteger();

        public Factory() {
            constructed.incrementAndGet();
        }

        @Override
        public ContextSlot<FlowId> slot() {
            return FlowIdContext.slot();
        }

        @Override
        public FlowId create() {
            check(ContextInternal.current() != null, "Factory has no caller context");
            return FlowId.builder().withId("environment-" + created.incrementAndGet()).build();
        }
    }

    /** Broken environment configuration must not poison explicit access. */
    public static final class InvalidFactory implements ContextItemFactory<FlowId> {
        static final AtomicInteger constructed = new AtomicInteger();

        public InvalidFactory() {
            constructed.incrementAndGet();
            throw new IllegalArgumentException("private-factory-configuration");
        }

        @Override
        public ContextSlot<FlowId> slot() {
            throw new AssertionError("Broken provider must not be used");
        }

        @Override
        public FlowId create() {
            throw new AssertionError("Broken provider must not be used");
        }
    }
}
