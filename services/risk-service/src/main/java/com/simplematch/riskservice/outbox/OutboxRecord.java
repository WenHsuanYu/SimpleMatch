package com.simplematch.riskservice.outbox;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable outbox row value object used by the risk-service submission flow.
 */
@SuppressWarnings("PMD.TooManyMethods") // Immutable outbox value exposes defensive-copy persistence accessors.
public final class OutboxRecord {
    private final String eventId;
    private final String topic;
    private final String messageKey;
    private final Integer kafkaPartitionId;
    private final byte[] payload;
    private final String payloadType;
    private final String headersJson;
    private final String aggregateType;
    private final String aggregateId;
    private final long createdAtUnixMs;

    private OutboxRecord(
            EventInfo event,
            Routing routing,
            PayloadEnvelope payloadEnvelope,
            AggregateRef aggregate) {
        this.eventId = event.eventId();
        this.topic = routing.topic();
        this.messageKey = routing.messageKey();
        this.kafkaPartitionId = routing.kafkaPartitionId();
        this.payload = payloadEnvelope.payload();
        this.payloadType = payloadEnvelope.payloadType();
        this.headersJson = payloadEnvelope.headersJson();
        this.aggregateType = aggregate.aggregateType();
        this.aggregateId = aggregate.aggregateId();
        this.createdAtUnixMs = event.createdAtUnixMs();
    }

    /**
     * Creates an outbox record from grouped event, routing, payload, and aggregate metadata.
     *
     * @param event           the event identity and creation timestamp
     * @param routing         the destination topic, message key, and optional partition
     * @param payloadEnvelope the serialized payload and headers persisted to the outbox
     * @param aggregate       the aggregate reference associated with the outbox row
     * @return a validated outbox record
     */
    public static OutboxRecord create(
            EventInfo event,
            Routing routing,
            PayloadEnvelope payloadEnvelope,
            AggregateRef aggregate) {
        return new OutboxRecord(event, routing, payloadEnvelope, aggregate);
    }

    /**
     * Event identity and creation timestamp for an outbox record.
     *
     * @param eventId         the unique event identifier
     * @param createdAtUnixMs the event creation time in epoch milliseconds
     */
    public record EventInfo(String eventId, long createdAtUnixMs) {
        public EventInfo {
            eventId = requireNonBlank(eventId, "eventId");
            if (createdAtUnixMs < 0) {
                throw new IllegalArgumentException("createdAtUnixMs must be >= 0");
            }
        }
    }

    /**
     * Routing metadata for an outbox record.
     *
     * @param topic            the destination topic
     * @param messageKey       the message key used for routing
     * @param kafkaPartitionId the optional Kafka partition override
     */
    public record Routing(String topic, String messageKey, Integer kafkaPartitionId) {
        public Routing {
            topic = requireNonBlank(topic, "topic");
            messageKey = requireNonBlank(messageKey, "messageKey");
            if (kafkaPartitionId != null && kafkaPartitionId < 0) {
                throw new IllegalArgumentException("kafkaPartitionId must be >= 0");
            }
        }

        /**
         * Creates routing metadata with an explicit partition.
         *
         * @param topic            the destination topic
         * @param messageKey       the message key used for routing
         * @param kafkaPartitionId the target partition id
         * @return routing metadata with a partition override
         */
        public static Routing withPartition(String topic, String messageKey, int kafkaPartitionId) {
            return new Routing(topic, messageKey, kafkaPartitionId);
        }

        /**
         * Creates routing metadata without an explicit partition.
         *
         * @param topic      the destination topic
         * @param messageKey the message key used for routing
         * @return routing metadata without a partition override
         */
        public static Routing withoutPartition(String topic, String messageKey) {
            return new Routing(topic, messageKey, null);
        }

        /**
         * Creates routing metadata from a nullable partition value.
         *
         * @param topic            the destination topic
         * @param messageKey       the message key used for routing
         * @param kafkaPartitionId the nullable partition id
         * @return routing metadata for the provided inputs
         */
        public static Routing of(String topic, String messageKey, Integer kafkaPartitionId) {
            return kafkaPartitionId == null
                    ? withoutPartition(topic, messageKey)
                    : withPartition(topic, messageKey, kafkaPartitionId);
        }
    }

    /**
     * Serialized payload and transport headers persisted with an outbox row.
     */
    public static final class PayloadEnvelope {
        private final byte[] payload;
        private final String payloadType;
        private final String headersJson;

        /**
         * Creates a payload envelope.
         *
         * @param payload     the serialized message payload
         * @param payloadType the payload schema or type identifier
         * @param headersJson the serialized transport headers
         */
        public PayloadEnvelope(byte[] payload, String payloadType, String headersJson) {
            this.payload = Arrays.copyOf(Objects.requireNonNull(payload), payload.length);
            this.payloadType = requireNonBlank(payloadType, "payloadType");
            this.headersJson = requireNonBlank(headersJson, "headersJson");
        }

        public byte[] payload() {
            return Arrays.copyOf(payload, payload.length);
        }

        public String payloadType() {
            return payloadType;
        }

        public String headersJson() {
            return headersJson;
        }
    }

    /**
     * Aggregate reference associated with an outbox row.
     *
     * @param aggregateType the aggregate type name
     * @param aggregateId   the aggregate identifier, which may be blank for validation failures
     */
    public record AggregateRef(String aggregateType, String aggregateId) {
        public AggregateRef {
            aggregateType = requireNonBlank(aggregateType, "aggregateType");
            Objects.requireNonNull(aggregateId, "aggregateId");
        }
    }

    public String eventId() {
        return eventId;
    }

    public String topic() {
        return topic;
    }

    public String messageKey() {
        return messageKey;
    }

    public Integer kafkaPartitionId() {
        return kafkaPartitionId;
    }

    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public String payloadType() {
        return payloadType;
    }

    public String headersJson() {
        return headersJson;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public String aggregateId() {
        return aggregateId;
    }

    public long createdAtUnixMs() {
        return createdAtUnixMs;
    }

    private static String requireNonBlank(String value, String fieldName) {
        final String resolved = Objects.requireNonNull(value, fieldName);
        if (resolved.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return resolved;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OutboxRecord that)) {
            return false;
        }
        return createdAtUnixMs == that.createdAtUnixMs
                && eventId.equals(that.eventId)
                && topic.equals(that.topic)
                && messageKey.equals(that.messageKey)
                && Objects.equals(kafkaPartitionId, that.kafkaPartitionId)
                && Arrays.equals(payload, that.payload)
                && payloadType.equals(that.payloadType)
                && headersJson.equals(that.headersJson)
                && aggregateType.equals(that.aggregateType)
                && aggregateId.equals(that.aggregateId);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                eventId,
                topic,
                messageKey,
                kafkaPartitionId,
                payloadType,
                headersJson,
                aggregateType,
                aggregateId,
                createdAtUnixMs);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }
}
