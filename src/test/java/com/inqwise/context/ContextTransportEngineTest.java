package com.inqwise.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.inqwise.context.ContextPropagationException.Direction;
import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.spi.ContextChainDataItem;
import com.inqwise.context.spi.ContextChainDataService;
import com.inqwise.context.spi.ContextTransportCarrier;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Timeout(10)
class ContextTransportEngineTest {
    @ParameterizedTest
    @MethodSource("containerImportConflicts")
    void engine_incompatibleContainerImport_preservesItemAndContinuesHealthyProviders(
            String container, String conflict, boolean populated, boolean observerThrows,
            Vertx vertx, VertxTestContext test) {
        List<Throwable> failures = new ArrayList<>();
        vertx.exceptionHandler(failure -> {
            failures.add(failure);
            if (observerThrows) {
                throw new IllegalStateException("private-observer-data");
            }
        });
        vertx.runOnContext(ignored -> test.verify(() -> {
            ContextInternal caller = ContextInternal.current();
            ContextMap<String, Integer> map = ContextMaps.define("container", String.class, Integer.class);
            ContextSet<String> set = ContextSets.define("container", String.class);
            ContextList<String> list = ContextLists.define("container", String.class);
            ContextSlot<?> owner = switch (container) {
                case "map" -> map.slot();
                case "set" -> set.slot();
                case "list" -> list.slot();
                default -> throw new IllegalArgumentException("Unknown container");
            };
            ContextChainDataItem original = switch (container) {
                case "map" -> map.item(populated ? Map.of("local", 1) : Map.of());
                case "set" -> set.item(populated ? Set.of("local") : Set.of());
                case "list" -> list.item(populated ? List.of("local", "local") : List.of());
                default -> throw new IllegalArgumentException("Unknown container");
            };
            DefaultStorage.put(caller, "container", original, true);
            AtomicReference<ContextChainDataItem> decoded = new AtomicReference<>(switch (conflict) {
                case "key" -> ContextMaps.define("container", Integer.class, Integer.class).item(Map.of());
                case "value" -> switch (container) {
                    case "map" -> ContextMaps.define("container", String.class, Long.class).item(Map.of());
                    case "set" -> ContextSets.define("container", Long.class).item(Set.of());
                    case "list" -> ContextLists.define("container", Long.class).item(List.of());
                    default -> throw new IllegalArgumentException("Unknown container");
                };
                case "map" -> map.item(Map.of());
                case "set" -> set.item(Set.of());
                case "list" -> list.item(List.of());
                case "item" -> new OptionalItem("private-decoded-data");
                default -> throw new IllegalArgumentException("Unknown conflict");
            });
            OptionalCodec codec = new OptionalCodec() {
                @Override
                public String key() {
                    return "container";
                }

                @Override
                public ContextChainDataItem unmarshalFormString(String wire) {
                    return decoded.get();
                }
            };
            FlowIdService healthy = new FlowIdService();
            MemoryCarrier carrier = new MemoryCarrier();
            carrier.set("container", "incoming");
            carrier.set("flow_id", healthy.marshalToString(FlowId.builder().withId("healthy").build()));
            ContextTransportEngine engine = new ContextTransportEngine(() -> List.of(codec, healthy));
            engine.read(caller, carrier, Set.of("container", "flow_id"));
            assertSame(original, owner.require());
            assertEquals("healthy", FlowIdContext.require().getId());
            assertEquals(1, failures.size());
            ContextPropagationException failure = assertInstanceOf(ContextPropagationException.class, failures.getFirst());
            assertEquals(Direction.INBOUND, failure.getDirection());
            assertEquals(codec.getClass().getName(), failure.getProviderClassName());
            assertEquals(ClassCastException.class.getName(), failure.getFailureType());
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains("private-decoded-data"));
            ContextChainDataItem valid = switch (container) {
                case "map" -> map.item(Map.of("recovered", 2));
                case "set" -> set.item(Set.of("recovered"));
                case "list" -> list.item(List.of("recovered", "recovered"));
                default -> throw new IllegalArgumentException("Unknown container");
            };
            decoded.set(valid);
            engine.read(caller, carrier, Set.of("container", "flow_id"));
            assertSame(valid, owner.require());
            assertEquals(1, failures.size());
            carrier.set("container", codec.marshalRemovalToString());
            engine.read(caller, carrier, Set.of("container", "flow_id"));
            assertTrue(DefaultStorage.isRemoved(DefaultStorage.getEntry(caller, "container")));
            test.completeNow();
        }));
    }

    private static Stream<Arguments> containerImportConflicts() {
        return Stream.of("map", "set", "list").flatMap(container ->
            (switch (container) {
                case "map" -> Stream.of("key", "value", "item", "set", "list");
                case "set" -> Stream.of("value", "item", "map", "list");
                case "list" -> Stream.of("value", "item", "map", "set");
                default -> throw new IllegalArgumentException("Unknown container");
            })
                .flatMap(conflict -> Stream.of(false, true).flatMap(populated -> Stream.of(false, true)
                    .map(observer -> Arguments.of(container, conflict, populated, observer)))));
    }

    @ParameterizedTest
    @MethodSource("decoderFailureModes")
    void engine_invalidDecode_preservesStorageAndRecoversOnNextMessage(
            String localState, boolean replaceExisting, boolean observerThrows, boolean nullResult,
            Vertx vertx, VertxTestContext context) {
        List<Throwable> failures = new ArrayList<>();
        vertx.exceptionHandler(failure -> {
            failures.add(failure);
            if (observerThrows) {
                throw new IllegalStateException("private-observer-state");
            }
        });
        AtomicBoolean invalid = new AtomicBoolean(true);
        AtomicInteger decoded = new AtomicInteger();
        OptionalCodec codec = new OptionalCodec() {
            @Override
            public boolean replaceExistingOnInbound() {
                return replaceExisting;
            }

            @Override
            public ContextChainDataItem unmarshalFormString(String encoded) {
                decoded.incrementAndGet();
                if (invalid.get()) {
                    if (nullResult) {
                        return null;
                    }
                    throw new IllegalArgumentException("private-decoder-state");
                }
                return super.unmarshalFormString(encoded);
            }
        };
        FlowIdService healthy = new FlowIdService();
        ContextTransportEngine engine = new ContextTransportEngine(() -> List.of(codec, healthy));
        inFlow(vertx, "caller", ignored -> {
            ContextInternal caller = ContextInternal.current();
            OptionalItem original = new OptionalItem("original");
            OptionalItem remote = new OptionalItem("remote");
            if (localState.equals("value")) {
                Storage.put("optional", original);
            } else if (localState.equals("removed")) {
                Storage.remove("optional");
            }
            ContextChainDataItem before = DefaultStorage.getEntry(caller, "optional");
            MemoryCarrier carrier = new MemoryCarrier();
            carrier.set("optional", codec.marshalToString(remote));
            FlowId flow = FlowId.builder().withId("healthy").build();
            carrier.set("flow_id", healthy.marshalToString(flow));
            engine.read(caller, carrier, Set.of("optional", "flow_id"));

            boolean imports = replaceExisting || localState.equals("empty");
            assertSame(before, DefaultStorage.getEntry(caller, "optional"));
            assertEquals(imports ? 1 : 0, decoded.get());
            assertEquals(imports ? 1 : 0, failures.size());
            if (imports) {
                ContextPropagationException failure = assertInstanceOf(ContextPropagationException.class, failures.getFirst());
                assertEquals(Direction.INBOUND, failure.getDirection());
                assertEquals(codec.getClass().getName(), failure.getProviderClassName());
                assertEquals((nullResult ? NullPointerException.class : IllegalArgumentException.class).getName(), failure.getFailureType());
                assertNull(failure.getCause());
                assertFalse(failure.toString().contains("private-decoder-state"));
            }
            FlowId imported = (FlowId) Storage.get("flow_id");
            assertEquals(flow.toJson(), imported.toJson());

            invalid.set(false);
            engine.read(caller, carrier, Set.of("optional", "flow_id"));
            assertSame(imported, Storage.get("flow_id"));
            assertEquals(imports ? 2 : 0, decoded.get());
            assertEquals(imports ? 1 : 0, failures.size());
            if (imports) {
                assertEquals(remote, Storage.get("optional"));
                assertNotSame(remote, Storage.get("optional"));
            } else {
                assertSame(before, DefaultStorage.getEntry(caller, "optional"));
            }
            MemoryCarrier forwarded = new MemoryCarrier();
            engine.write(caller, forwarded, Set.of("optional", "flow_id"));
            String expected = !imports && localState.equals("removed") ? codec.marshalRemovalToString()
                : codec.marshalToString(imports ? remote : original);
            assertEquals(List.of(expected), forwarded.getAll("optional"));
            assertEquals(List.of(healthy.marshalToString(flow)), forwarded.getAll("flow_id"));
            assertEquals(imports ? 1 : 0, failures.size());
            return Future.succeededFuture();
        }).onComplete(context.succeedingThenComplete());
    }

    private static Stream<Arguments> decoderFailureModes() {
        return Stream.of("empty", "value", "removed").flatMap(state -> Stream.of(false, true)
            .flatMap(replace -> Stream.of(false, true).flatMap(observer -> Stream.of(false, true)
                .map(nullResult -> Arguments.of(state, replace, observer, nullResult)))));
    }

    @ParameterizedTest
    @MethodSource("nullEncodingModes")
    void engine_nullEncoding_omitsStaleHeadersAndDistinguishesUnsupportedRemoval(
            boolean removal, boolean observerThrows, Vertx vertx, VertxTestContext context) {
        List<Throwable> failures = new ArrayList<>();
        vertx.exceptionHandler(failure -> {
            failures.add(failure);
            if (observerThrows) {
                throw new IllegalStateException("private-observer-state");
            }
        });
        OptionalCodec codec = new OptionalCodec() {
            @Override
            public String marshalToString(ContextChainDataItem value) {
                return null;
            }

            @Override
            public String marshalRemovalToString() {
                return null;
            }
        };
        FlowIdService healthy = new FlowIdService();
        ContextTransportEngine engine = new ContextTransportEngine(() -> List.of(codec, healthy));
        inFlow(vertx, "caller", ignored -> {
            ContextInternal caller = ContextInternal.current();
            OptionalItem original = new OptionalItem("original");
            Storage.put("optional", original);
            if (removal) {
                Storage.remove("optional");
            }
            ContextChainDataItem before = DefaultStorage.getEntry(caller, "optional");
            FlowId flow = FlowId.builder().withId("healthy").build();
            Storage.put("flow_id", flow);
            MemoryCarrier carrier = new MemoryCarrier();
            carrier.values.put("optional", List.of("stale", "duplicate"));
            carrier.set("application", "retained");
            engine.write(caller, carrier, Set.of("optional", "flow_id"));

            assertTrue(carrier.getAll("optional").isEmpty());
            assertEquals(List.of(healthy.marshalToString(flow)), carrier.getAll("flow_id"));
            assertEquals(List.of("retained"), carrier.getAll("application"));
            assertSame(before, DefaultStorage.getEntry(caller, "optional"));
            assertSame(flow, Storage.get("flow_id"));
            assertEquals(removal ? 0 : 1, failures.size());
            if (!removal) {
                ContextPropagationException failure = assertInstanceOf(ContextPropagationException.class, failures.getFirst());
                assertEquals(Direction.OUTBOUND, failure.getDirection());
                assertEquals(codec.getClass().getName(), failure.getProviderClassName());
                assertEquals(NullPointerException.class.getName(), failure.getFailureType());
                assertNull(failure.getCause());
            }
            return Future.succeededFuture();
        }).onComplete(context.succeedingThenComplete());
    }

    private static Stream<Arguments> nullEncodingModes() {
        return Stream.of(false, true).flatMap(removal -> Stream.of(false, true)
            .map(observer -> Arguments.of(removal, observer)));
    }

    @ParameterizedTest
    @MethodSource("replacementModes")
    void engine_customProviderPolicy_appliesEquallyToValuesAndRemovals(String localState,
            boolean replaceExisting, boolean remoteRemoval, Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        OptionalCodec codec = replaceExisting ? new OptionalCodec() : new OptionalCodec() {
            @Override
            public boolean replaceExistingOnInbound() {
                return false;
            }
        };
        ContextTransportEngine engine = new ContextTransportEngine(() -> List.of(codec, new FlowIdService()));
        inFlow(vertx, "caller", ignored -> {
            OptionalItem original = new OptionalItem("local");
            FlowId flow = FlowId.builder().withId("local-flow").build();
            Storage.put("flow_id", flow);
            if (localState.equals("value")) {
                Storage.put("optional", original);
            } else if (localState.equals("removed")) {
                Storage.remove("optional");
            }
            MemoryCarrier carrier = new MemoryCarrier();
            carrier.set("optional", remoteRemoval ? codec.marshalRemovalToString() : codec.marshalToString(new OptionalItem("remote")));
            carrier.set("flow_id", new FlowIdService().marshalToString(FlowId.builder().withId("remote-flow").build()));
            carrier.accessed.clear();
            engine.read(ContextInternal.current(), carrier, Set.of("optional", "flow_id"));

            boolean imported = replaceExisting || localState.equals("empty");
            boolean removed = imported ? remoteRemoval : localState.equals("removed");
            if (removed) {
                assertNull(Storage.get("optional"));
            } else if (imported) {
                assertEquals(new OptionalItem("remote"), Storage.get("optional"));
                assertNotSame(original, Storage.get("optional"));
            } else {
                assertSame(original, Storage.get("optional"));
            }
            if (!imported) {
                assertFalse(carrier.accessed.contains("optional"));
            }
            assertSame(flow, Storage.get("flow_id"));
            assertFalse(carrier.accessed.contains("flow_id"));
            MemoryCarrier forwarded = new MemoryCarrier();
            engine.write(ContextInternal.current(), forwarded, Set.of("optional", "flow_id"));
            assertEquals(List.of(removed ? codec.marshalRemovalToString()
                : codec.marshalToString(new OptionalItem(imported ? "remote" : "local"))), forwarded.getAll("optional"));
            return Future.succeededFuture();
        }).onComplete(context.succeedingThenComplete());
    }

    private static Stream<Arguments> replacementModes() {
        return Stream.of("empty", "value", "removed").flatMap(state -> Stream.of(false, true)
            .flatMap(replace -> Stream.of(false, true).map(removal -> Arguments.of(state, replace, removal))));
    }

    @ParameterizedTest
    @MethodSource("codecErrors")
    void engine_codecError_reportsProviderAndContinuesHealthyMetadata(Direction direction,
            boolean removal, boolean observerThrows, String failureKind,
            Vertx vertx, VertxTestContext context) {
        String secret = "private-codec-state";
        Error failure = switch (failureKind) {
            case "linkage" -> new NoClassDefFoundError(secret);
            case "initialization" -> new ExceptionInInitializerError(secret);
            case "assertion" -> new AssertionError(secret);
            case "service" -> new ServiceConfigurationError(secret, new IllegalStateException(secret));
            case "stack" -> new AssertionError(secret) {
                @Override
                public StackTraceElement[] getStackTrace() {
                    throw new IllegalStateException(secret);
                }
            };
            default -> throw new IllegalArgumentException("Unknown codec error scenario");
        };
        List<Throwable> errors = new ArrayList<>();
        vertx.exceptionHandler(error -> {
            errors.add(error);
            if (observerThrows) {
                throw new IllegalStateException(secret);
            }
        });
        OptionalCodec broken = new OptionalCodec() {
            @Override
            public String marshalToString(ContextChainDataItem item) {
                throw failure;
            }

            @Override
            public String marshalRemovalToString() {
                throw failure;
            }

            @Override
            public ContextChainDataItem unmarshalFormString(String encoded) {
                throw failure;
            }

            @Override
            public boolean isRemoval(String encoded) {
                if (removal) {
                    throw failure;
                }
                return false;
            }
        };
        FlowIdService healthy = new FlowIdService();
        ContextTransportEngine engine = new ContextTransportEngine(() -> List.of(broken, healthy));
        inFlow(vertx, "caller", ignored -> {
            MemoryCarrier carrier = new MemoryCarrier();
            FlowId flow = FlowId.builder().withId("healthy").build();
            OptionalItem original = new OptionalItem(secret);
            carrier.set("optional", "stale-metadata");
            if (direction == Direction.OUTBOUND) {
                Storage.put("optional", original);
                if (removal) {
                    Storage.remove("optional");
                }
                Storage.put("flow_id", flow);
                engine.write(ContextInternal.current(), carrier, Set.of("optional", "flow_id"));
                assertTrue(carrier.getAll("optional").isEmpty());
                assertEquals(flow.toJson(), ((FlowId) healthy.unmarshalFormString(carrier.getAll("flow_id").getFirst())).toJson());
                assertSame(removal ? null : original, Storage.get("optional"));
            } else {
                carrier.set("flow_id", healthy.marshalToString(flow));
                engine.read(ContextInternal.current(), carrier, Set.of("optional", "flow_id"));
                assertEquals(flow.toJson(), ((FlowId) Storage.get("flow_id")).toJson());
                assertNull(Storage.get("optional"));
            }
            assertEquals(1, errors.size());
            ContextPropagationException reported = assertInstanceOf(ContextPropagationException.class, errors.getFirst());
            assertEquals(direction, reported.getDirection());
            assertEquals(broken.getClass().getName(), reported.getProviderClassName());
            assertEquals(failure.getClass().getName(), reported.getFailureType());
            assertNull(reported.getCause());
            assertFalse(reported.toString().contains(secret));
            return Future.succeededFuture();
        }).onComplete(context.succeedingThenComplete());
    }

    private static Stream<Arguments> codecErrors() {
        return Stream.of(Direction.values()).flatMap(direction -> Stream.of(false, true)
            .flatMap(removal -> Stream.of(false, true).flatMap(observer -> Stream.of("linkage", "initialization", "assertion", "service", "stack")
                .map(kind -> Arguments.of(direction, removal, observer, kind)))));
    }

    private record OptionalItem(String value) implements ContextChainDataItem {
    }

    private static class OptionalCodec implements ContextChainDataService {
        @Override
        public String key() {
            return "optional";
        }

        @Override
        public String marshalToString(ContextChainDataItem item) {
            return "item:1:value:" + ((OptionalItem) item).value();
        }

        @Override
        public ContextChainDataItem unmarshalFormString(String encoded) {
            String prefix = "item:1:value:";
            if (!encoded.startsWith(prefix)) {
                throw new IllegalArgumentException("Invalid optional item envelope");
            }
            return new OptionalItem(encoded.substring(prefix.length()));
        }

        @Override
        public String marshalRemovalToString() {
            return "item:1:removed:";
        }

        @Override
        public boolean isRemoval(String encoded) {
            return encoded.equals(marshalRemovalToString());
        }
    }

    @Test
    void publicApi_roundTrip_preservesCallerStateAndCarriesRemoteChanges(Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        FlowId original = FlowId.builder().withId("caller").build();
        FlowId remote = FlowId.builder().withId("remote").build();
        FlowIdService codec = new FlowIdService();
        inFlow(vertx, "caller", ignored -> {
            Storage.put("flow_id", original);
            Storage.put("private", remote);
            MemoryCarrier request = new MemoryCarrier();
            request.values.put("flow_id", List.of("stale", "duplicate"));
            request.set("unrelated", "retained");
            ContextTransport.writeTo(request);
            assertEquals(List.of(codec.marshalToString(original)), request.getAll("flow_id"));
            assertFalse(request.values.containsKey("private"));
            assertEquals(List.of("retained"), request.getAll("unrelated"));

            return inNewContext(vertx, () -> {
                assertNull(Storage.get("flow_id"));
                ContextTransport.readFrom(request);
                FlowId received = (FlowId) Storage.get("flow_id");
                assertNotSame(original, received);
                assertEquals(original.toJson(), received.toJson());
                assertNull(Storage.get("private"));
                Storage.put("flow_id", remote);
                MemoryCarrier response = new MemoryCarrier();
                ContextTransport.writeTo(response);
                return response;
            }).map(response -> {
                ContextTransport.readFrom(response);
                assertSame(original, Storage.get("flow_id"));
                assertEquals(remote.toJson(), ((FlowId) codec.unmarshalFormString(response.getAll("flow_id").getFirst())).toJson());
                return response;
            });
        }).onComplete(context.succeedingThenComplete());
    }

    @Test
    void publicApi_removal_roundTripsAndAllowsExplicitReaddition(Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        FlowIdService codec = new FlowIdService();
        inFlow(vertx, "removed", ignored -> {
            Storage.remove("flow_id");
            MemoryCarrier request = new MemoryCarrier();
            ContextTransport.writeTo(request);
            assertTrue(codec.isRemoval(request.getAll("flow_id").getFirst()));
            return inNewContext(vertx, () -> {
                ContextTransport.readFrom(request);
                assertNull(Storage.get("flow_id"));
                MemoryCarrier response = new MemoryCarrier();
                ContextTransport.writeTo(response);
                assertTrue(codec.isRemoval(response.getAll("flow_id").getFirst()));
                Storage.put("flow_id", FlowId.builder().withId("re-added").build());
                ContextTransport.writeTo(response);
                return response;
            }).map(response -> {
                ContextTransport.readFrom(response);
                assertNull(Storage.get("flow_id"));
                assertEquals("re-added", ((FlowId) codec.unmarshalFormString(response.getAll("flow_id").getFirst())).getId());
                FlowId restored = FlowId.builder().withId("caller-re-added").build();
                Storage.put("flow_id", restored);
                ContextTransport.writeTo(request);
                assertEquals(restored.toJson(), ((FlowId) codec.unmarshalFormString(request.getAll("flow_id").getFirst())).toJson());
                return response;
            });
        }).onComplete(context.succeedingThenComplete());
    }

    @Test
    void engine_allowlist_doesNotTouchUnselectedHeadersOrStorage(Vertx vertx, VertxTestContext context) {
        FlowIdService codec = new FlowIdService();
        FlowIdService excluded = new FlowIdService() {
            @Override
            public String key() {
                return "private";
            }
        };
        ContextTransportEngine engine = new ContextTransportEngine(() -> List.of(codec, excluded));
        vertx.exceptionHandler(context::failNow);
        vertx.runOnContext(ignored -> context.verify(() -> {
            FlowId secret = FlowId.builder().withId("local-private").build();
            Storage.put("private", secret);
            MemoryCarrier carrier = new MemoryCarrier();
            carrier.set("private", "explicit-private");
            carrier.set("flow_id", codec.marshalToString(FlowId.builder().withId("imported").build()));
            carrier.accessed.clear();

            engine.read(ContextInternal.current(), carrier, Set.of("flow_id"));
            engine.write(ContextInternal.current(), carrier, Set.of("flow_id"));

            assertEquals("imported", ((FlowId) Storage.get("flow_id")).getId());
            assertSame(secret, Storage.get("private"));
            assertEquals(List.of("explicit-private"), carrier.values.get("private"));
            assertFalse(carrier.accessed.contains("private"));
            context.completeNow();
        }));
    }

    @Test
    void publicApi_emptyAllowlist_doesNotAccessCarrier(Vertx vertx, VertxTestContext context) {
        vertx.exceptionHandler(context::failNow);
        vertx.runOnContext(ignored -> context.verify(() -> {
            MemoryCarrier carrier = new MemoryCarrier();
            carrier.set("flow_id", "explicit");
            carrier.accessed.clear();
            var empty = ContextTransportConfiguration.builder().build();

            ContextTransport.readFrom(carrier, empty);
            ContextTransport.writeTo(carrier, empty);

            assertTrue(carrier.accessed.isEmpty());
            assertEquals(List.of("explicit"), carrier.values.get("flow_id"));
            assertNull(Storage.get("flow_id"));
            context.completeNow();
        }));
    }

    @ParameterizedTest
    @EnumSource(Direction.class)
    void publicApi_carrierFailure_reportsSanitizedErrorWithoutThrowing(
            Direction direction, Vertx vertx, VertxTestContext context) {
        List<Throwable> failures = new ArrayList<>();
        vertx.exceptionHandler(failures::add);
        vertx.runOnContext(ignored -> context.verify(() -> {
            FlowId original = FlowId.builder().withId("local").build();
            ContextTransportCarrier carrier = new MemoryCarrier() {
                @Override
                public List<String> getAll(String key) {
                    throw new IllegalArgumentException("private-carrier-data");
                }

                @Override
                public void set(String key, String value) {
                    throw new IllegalArgumentException("private-carrier-data");
                }
            };
            if (direction == Direction.OUTBOUND) {
                Storage.put("flow_id", original);
                ContextTransport.writeTo(carrier);
                assertSame(original, Storage.get("flow_id"));
            } else {
                ContextTransport.readFrom(carrier);
                assertNull(Storage.get("flow_id"));
            }
            assertEquals(1, failures.size());
            var failure = assertInstanceOf(ContextPropagationException.class, failures.getFirst());
            assertEquals(direction, failure.getDirection());
            assertFalse(failure.toString().contains("private-carrier-data"));
            assertNull(failure.getCause());
            context.completeNow();
        }));
    }

    @Test
    void publicApi_invalidExecutionOrCarrier_failsExplicitly() {
        MemoryCarrier carrier = new MemoryCarrier();
        var explicit = ContextTransportConfiguration.builder().build();
        assertThrows(IllegalStateException.class, () -> ContextTransport.readFrom(carrier));
        assertThrows(IllegalStateException.class, () -> ContextTransport.writeTo(carrier));
        assertThrows(IllegalStateException.class, () -> ContextTransport.readFrom(carrier, explicit));
        assertThrows(IllegalStateException.class, () -> ContextTransport.writeTo(carrier, explicit));
        assertThrows(NullPointerException.class, () -> ContextTransport.readFrom(null));
        assertThrows(NullPointerException.class, () -> ContextTransport.writeTo(null));
    }

    private static Future<MemoryCarrier> inNewContext(Vertx vertx, Supplier<MemoryCarrier> operation) {
        Promise<MemoryCarrier> result = Promise.promise();
        ContextInternal incoming = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        incoming.runOnContext(ignored -> {
            try {
                result.complete(operation.get());
            } catch (Throwable failure) {
                result.fail(failure);
            }
        });
        return ContextPropagation.bind(result.future());
    }

    private static class MemoryCarrier implements ContextTransportCarrier {
        final Map<String, List<String>> values = new LinkedHashMap<>();
        final List<String> accessed = new ArrayList<>();

        @Override
        public List<String> getAll(String key) {
            accessed.add(key);
            return values.getOrDefault(key, List.of());
        }

        @Override
        public void set(String key, String value) {
            accessed.add(key);
            values.put(key, List.of(value));
        }

        @Override
        public void remove(String key) {
            accessed.add(key);
            values.remove(key);
        }
    }
}
