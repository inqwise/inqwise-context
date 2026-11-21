package com.inqwise.context;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.internal.ContextInternal;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import io.vertx.kafka.client.producer.RecordMetadata;

/**
 * Opt-in one-way Kafka metadata propagation. Applications using this adapter
 * must include the Vert.x Kafka client. Broker acknowledgments are not consumer
 * replies; consumer changes do not update the producer's stored metadata.
 */
public final class KafkaContextTransport {
    private static final ContextTransportEngine engine = new ContextTransportEngine(ContextChainStorageHolder::getDataProviders);

    private KafkaContextTransport() {
    }

    /**
     * Creates a sender using shared default configuration.
     *
     * @param producer the application-owned producer
     * @param allowedTopics exact topics allowed to export metadata; none disables export
     * @param <K> the record key type
     * @param <V> the record value type
     * @return a sender that does not own the producer lifecycle
     * @throws NullPointerException if an argument or topic is null
     * @throws IllegalArgumentException if a topic is blank or a selected key is invalid
     * @throws IllegalStateException if default configuration cannot resolve
     */
    public static <K, V> Sender<K, V> sender(KafkaProducer<K, V> producer, String... allowedTopics) {
        return sender(producer, null, allowedTopics);
    }

    /**
     * Creates a sender with captured configuration and exact, case-sensitive
     * topic selection. Sending to other topics still delivers the payload, but
     * strips managed metadata headers and exports no stored metadata.
     *
     * @param producer the application-owned producer
     * @param configuration the explicit configuration, or null for the shared default
     * @param allowedTopics the exact topics allowed to export metadata
     * @param <K> the record key type
     * @param <V> the record value type
     * @return a new sender
     * @throws NullPointerException if the producer, topic array, or a topic is null
     * @throws IllegalArgumentException if a topic is blank or a selected key is invalid
     * @throws IllegalStateException if configuration cannot resolve
     */
    public static <K, V> Sender<K, V> sender(KafkaProducer<K, V> producer,
            ContextTransportConfiguration configuration, String... allowedTopics) {
        Objects.requireNonNull(producer, "Producer is mandatory");
        return new Sender<>(producer, validated(configuration), topics(allowedTopics));
    }

    /**
     * Wraps a record handler using shared default configuration.
     *
     * @param application the application handler
     * @param allowedTopics exact topics allowed to import metadata; none disables import
     * @param <K> the record key type
     * @param <V> the record value type
     * @return the per-record context adapter
     * @throws NullPointerException if an argument or topic is null
     * @throws IllegalArgumentException if a topic is blank or a selected key is invalid
     * @throws IllegalStateException if default configuration cannot resolve
     */
    public static <K, V> Handler<KafkaConsumerRecord<K, V>> handler(
            Handler<KafkaConsumerRecord<K, V>> application, String... allowedTopics) {
        return handler(null, application, allowedTopics);
    }

    /**
     * Runs each record in a duplicated context with fresh library storage.
     * Other locals follow Vert.x's context duplication semantics. Denied topics still reach
     * the application with empty storage. Invoke from a Vert.x consumer callback
     * or another active context; the consumer record itself is not changed.
     * This adapter does not commit, pause, resume, retry, or await application work.
     * Synchronous application failures propagate unchanged to the invoking
     * consumer after restoring its context, preserving native tracing/error handling.
     *
     * @param configuration the explicit configuration, or null for the shared default
     * @param application the record handler
     * @param allowedTopics exact topics allowed to import metadata
     * @param <K> the record key type
     * @param <V> the record value type
     * @return the per-record adapter
     * @throws NullPointerException if an argument other than configuration is null
     * @throws IllegalArgumentException if a topic is blank or a selected key is invalid
     * @throws IllegalStateException if configuration cannot resolve or invoked without a context
     */
    public static <K, V> Handler<KafkaConsumerRecord<K, V>> handler(ContextTransportConfiguration configuration,
            Handler<KafkaConsumerRecord<K, V>> application, String... allowedTopics) {
        Objects.requireNonNull(application, "Record handler is mandatory");
        ContextTransportConfiguration selected = validated(configuration);
        Set<String> topics = topics(allowedTopics);
        return record -> {
            Objects.requireNonNull(record, "Record is mandatory");
            ContextInternal scope = currentContext().duplicate(true);
            DefaultStorage.reset(scope);
            // Let the native consumer observe application failures instead of catching them in emit.
            ContextInternal previous = scope.beginDispatch();
            try {
                if (topics.contains(record.topic())) {
                    engine.read(scope, new KafkaContextCarrier(record::headers), selected.includedKeys());
                }
                application.handle(record);
            } finally {
                scope.endDispatch(previous);
            }
        };
    }

    /**
     * A small sending facade; use the native producer for lifecycle and transactions.
     *
     * @param <K> the record key type
     * @param <V> the record value type
     */
    public static final class Sender<K, V> {
        private final KafkaProducer<K, V> producer;
        private final ContextTransportConfiguration configuration;
        private final Set<String> topics;

        private Sender(KafkaProducer<K, V> producer, ContextTransportConfiguration configuration, Set<String> topics) {
            this.producer = producer;
            this.configuration = configuration;
            this.topics = topics;
        }

        /**
         * Sends a new record with copied headers, leaving the input record and
         * its buffers unchanged. Key and value objects are not deep-copied.
         * The returned acknowledgment future is bound to the sending context.
         * Codec failures omit metadata without failing submission; producer
         * submission or acknowledgment failures still fail the returned future.
         *
         * @param record the original record
         * @return the broker acknowledgment, not a consumer result
         * @throws NullPointerException if the record is null
         * @throws IllegalStateException if called outside a Vert.x context
         */
        public Future<RecordMetadata> send(KafkaProducerRecord<K, V> record) {
            Objects.requireNonNull(record, "Record is mandatory");
            ContextInternal caller = currentContext();
            try {
                KafkaProducerRecord<K, V> copy = KafkaProducerRecord.create(record.topic(), record.key(), record.value(),
                    record.timestamp(), record.partition());
                for (var header : record.headers()) {
                    Buffer value = header.value();
                    copy.addHeader(header.key(), value == null ? null : value.copy());
                }
                KafkaContextCarrier.clear(copy.headers());
                if (topics.contains(copy.topic())) {
                    engine.write(caller, new KafkaContextCarrier(copy::headers), configuration.includedKeys());
                }
                return ContextPropagation.bind(producer.send(copy));
            } catch (RuntimeException failure) {
                return caller.failedFuture(failure);
            }
        }
    }

    private static ContextTransportConfiguration validated(ContextTransportConfiguration configuration) {
        ContextTransportConfiguration selected = ContextTransport.resolveConfiguration(configuration);
        selected.includedKeys().forEach(KafkaContextCarrier::name);
        return selected;
    }

    private static Set<String> topics(String[] allowed) {
        Objects.requireNonNull(allowed, "Topics are mandatory");
        Set<String> result = new HashSet<>();
        for (String topic : allowed) {
            Objects.requireNonNull(topic, "Topic is mandatory");
            if (topic.isBlank()) {
                throw new IllegalArgumentException("Topic must not be blank");
            }
            result.add(topic);
        }
        return Set.copyOf(result);
    }

    private static ContextInternal currentContext() {
        ContextInternal context = ContextInternal.current();
        if (context == null) {
            throw new IllegalStateException("Kafka context transport requires a calling Vert.x context");
        }
        return context;
    }
}
