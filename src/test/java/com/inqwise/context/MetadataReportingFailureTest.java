package com.inqwise.context;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.inqwise.context.ContextPropagationException.Direction;
import com.inqwise.context.data.FlowId;
import com.inqwise.context.data.FlowIdService;
import com.inqwise.context.spi.ContextChainDataItem;
import com.inqwise.context.spi.ContextChainDataService;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.kafka.client.consumer.impl.KafkaConsumerRecordImpl;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.inqwise.context.ClientContextTestSupport.inFlow;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
@ExtendWith(VertxExtension.class)
@Timeout(15)
class MetadataReportingFailureTest {
    private LoggerContext logging;
    private Configuration configuration;
    private String loggerName;
    private LoggerConfig previous;
    private AbstractAppender appender;
    private boolean configured;

    @ParameterizedTest
    @MethodSource("discoveryModes")
    void discovery_failingWarningLogger_retainsHealthyProvidersAndRetryBound(
            String warning, String loggingFailure, @TempDir Path directory) throws Exception {
        List<LogEvent> diagnostics = failLogger(ContextChainStorageHolder.class, Level.WARN, loggingFailure);
        String descriptorName = "META-INF/services/" + ContextChainDataService.class.getName();
        String invalid = switch (warning) {
            case "load" -> ContextChainStorageHolderTest.ThrowingProvider.class.getName();
            case "key" -> ContextChainStorageHolderTest.ThrowingKeyProvider.class.getName();
            case "missing" -> ContextChainStorageHolderTest.MissingKeyProvider.class.getName();
            case "duplicate" -> ContextChainStorageHolderTest.DuplicateProvider.class.getName();
            default -> null;
        };
        Path descriptor = directory.resolve(descriptorName);
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, (invalid == null ? "" : invalid + "\n")
            + ContextChainStorageHolderTest.HealthyProvider.class.getName() + "\n");
        AtomicInteger probes = new AtomicInteger();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                Enumeration<URL> resources = super.getResources(name);
                if (!warning.equals("limit") || !name.equals(descriptorName)) {
                    return resources;
                }
                return new Enumeration<>() {
                    @Override
                    public boolean hasMoreElements() {
                        if (resources.hasMoreElements()) {
                            return true;
                        }
                        if (probes.incrementAndGet() > 64) {
                            throw new DiscoveryProbeExhausted();
                        }
                        throw new ServiceConfigurationError("Private discovery detail");
                    }

                    @Override
                    public URL nextElement() {
                        return resources.nextElement();
                    }
                };
            }
        }) {
            var providers = assertDoesNotThrow(() -> ContextChainStorageHolder.discoverProviders(
                ServiceLoader.load(ContextChainDataService.class, loader)));
            assertEquals(List.of(FlowIdService.class, ContextChainStorageHolderTest.HealthyProvider.class),
                providers.stream().map(Object::getClass).toList());
            assertThrows(UnsupportedOperationException.class, providers::clear);
            assertEquals(warning.equals("limit") ? 64 : 0, probes.get());
            assertEquals(warning.equals("limit") ? 65 : 1, diagnostics.size());
            diagnostics.forEach(event -> {
                assertFalse(event.getMessage().getFormattedMessage().contains("Private"));
                assertNull(event.getThrown());
            });
        }
    }

    @ParameterizedTest
    @MethodSource("reportingModes")
    void reporting_failingObserverAndLogger_continueHealthyMetadataAndPayload(
            Direction direction, String loggingFailure, Vertx vertx, VertxTestContext testContext) {
        String secret = "private-codec-state";
        List<Throwable> observed = new CopyOnWriteArrayList<>();
        List<LogEvent> diagnostics = failLogger(ContextTransportEngine.class, Level.ERROR, loggingFailure);
        vertx.exceptionHandler(error -> {
            observed.add(error);
            throw new IllegalStateException("private-observer-state");
        });
        AtomicBoolean broken = new AtomicBoolean(true);
        FlowIdService codec = new FlowIdService() {
            @Override
            public String key() {
                return "broken";
            }

            @Override
            public boolean replaceExistingOnInbound() {
                return true;
            }

            @Override
            public String marshalToString(ContextChainDataItem item) {
                if (broken.get()) {
                    throw new IllegalArgumentException(secret);
                }
                return super.marshalToString(item);
            }

            @Override
            public ContextChainDataItem unmarshalFormString(String encoded) {
                if (broken.get()) {
                    throw new IllegalArgumentException(secret);
                }
                return super.unmarshalFormString(encoded);
            }
        };
        FlowIdService healthy = new FlowIdService();
        ContextTransportEngine engine = new ContextTransportEngine(() -> List.of(codec, healthy));
        inFlow(vertx, "reporting-failure", ignored -> {
            ContextInternal caller = ContextInternal.current();
            FlowId original = FlowId.builder().withId("original").build();
            FlowId flow = FlowId.builder().withId("healthy").build();
            Storage.put("broken", original);
            Storage.put("private", original);
            HttpContextCarrier carrier = new HttpContextCarrier(MultiMap.caseInsensitiveMultiMap());
            carrier.set("broken", healthy.marshalToString(flow));
            if (direction == Direction.OUTBOUND) {
                Storage.put("flow_id", flow);
                assertDoesNotThrow(() -> engine.write(caller, carrier, Set.of("broken", "flow_id")));
                assertTrue(carrier.getAll("broken").isEmpty());
                assertEquals(flow.toJson(), ((FlowId) healthy.unmarshalFormString(carrier.getAll("flow_id").getFirst())).toJson());
            } else {
                carrier.set("flow_id", healthy.marshalToString(flow));
                assertDoesNotThrow(() -> engine.read(caller, carrier, Set.of("broken", "flow_id")));
                assertEquals(flow.toJson(), ((FlowId) Storage.get("flow_id")).toJson());
            }
            assertSame(original, Storage.get("broken"));
            assertSame(original, Storage.get("private"));
            assertEquals(1, observed.size());
            assertEquals(1, diagnostics.size());
            String diagnostic = diagnostics.getFirst().getMessage().getFormattedMessage();
            assertFalse(diagnostic.contains(secret));
            assertFalse(diagnostic.contains("private-observer-state"));
            assertNull(diagnostics.getFirst().getThrown());
            broken.set(false);
            if (direction == Direction.OUTBOUND) {
                engine.write(caller, carrier, Set.of("broken", "flow_id"));
                assertEquals(original.toJson(), ((FlowId) healthy.unmarshalFormString(carrier.getAll("broken").getFirst())).toJson());
            } else {
                engine.read(caller, carrier, Set.of("broken", "flow_id"));
                assertEquals(flow.toJson(), ((FlowId) Storage.get("broken")).toJson());
            }
            var current = Storage.get("flow_id");
            KafkaProducerRecord<String, String> record = KafkaProducerRecord.create("orders", "key", "payload");
            new KafkaContextCarrier(record::headers).set("flow_id", "flow-id:99:json:{}");
            var sent = record.record();
            ConsumerRecord<String, String> incoming = new ConsumerRecord<>(sent.topic(), 0, 0, sent.key(), sent.value());
            sent.headers().forEach(header -> incoming.headers().add(header.key(), header.value().clone()));
            AtomicInteger delivered = new AtomicInteger();
            var handler = KafkaContextTransport.<String, String>handler(message -> {
                assertNotSame(caller, ContextInternal.current());
                assertEquals("payload", message.value());
                assertNull(Storage.get("flow_id"));
                assertNull(Storage.get("private"));
                delivered.incrementAndGet();
            }, "orders");
            assertDoesNotThrow(() -> handler.handle(new KafkaConsumerRecordImpl<>(incoming)));
            assertEquals(1, delivered.get());
            assertSame(caller, ContextInternal.current());
            assertSame(current, Storage.get("flow_id"));
            return vertx.timer(1).map(tick -> {
                assertSame(caller, ContextInternal.current());
                assertSame(current, Storage.get("flow_id"));
                assertSame(original, Storage.get("private"));
                assertEquals(2, observed.size());
                assertEquals(2, diagnostics.size());
                return tick;
            });
        }).onComplete(testContext.succeedingThenComplete());
    }

    private List<LogEvent> failLogger(Class<?> owner, Level level, String failure) {
        List<LogEvent> diagnostics = new CopyOnWriteArrayList<>();
        appender = new AbstractAppender("controlled-reporting-failure", null,
                PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                diagnostics.add(event.toImmutable());
                switch (failure) {
                    case "assertion" -> throw new AssertionError("Controlled logging error");
                    case "linkage" -> throw new NoClassDefFoundError("Controlled logging linkage failure");
                    default -> throw new IllegalStateException("Controlled logging failure");
                }
            }
        };
        logging = (LoggerContext) LogManager.getContext(false);
        configuration = logging.getConfiguration();
        loggerName = owner.getName();
        previous = configuration.getLoggers().get(loggerName);
        LoggerConfig failing = new LoggerConfig(loggerName, level, false);
        appender.start();
        failing.addAppender(appender, level, null);
        configured = true;
        configuration.removeLogger(loggerName);
        configuration.addLogger(loggerName, failing);
        logging.updateLoggers();
        return diagnostics;
    }

    @AfterEach
    void restoreLogging() {
        try {
            if (configured) {
                configuration.removeLogger(loggerName);
                if (previous != null) {
                    configuration.addLogger(loggerName, previous);
                }
                logging.updateLoggers();
            }
        } finally {
            if (appender != null) {
                appender.stop();
            }
        }
    }

    private static final class DiscoveryProbeExhausted extends Error {
        private static final long serialVersionUID = 1L;
    }

    private static Stream<Arguments> discoveryModes() {
        return Stream.of("load", "key", "missing", "duplicate", "limit")
            .flatMap(warning -> Stream.of("runtime", "assertion", "linkage")
                .map(failure -> Arguments.of(warning, failure)));
    }

    private static Stream<Arguments> reportingModes() {
        return Stream.of(Direction.values()).flatMap(direction -> Stream.of("runtime", "assertion", "linkage")
            .map(failure -> Arguments.of(direction, failure)));
    }
}
