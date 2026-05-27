package com.simplematch.riskservice.submission;

import java.util.Arrays;
import java.util.Objects;

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

    public OutboxRecord(
            String eventId,
            String topic,
            String messageKey,
        byte[] payload,
        String payloadType,
        String headersJson,
        String aggregateType,
        String aggregateId,
        long createdAtUnixMs) {
    this(
        eventId,
        topic,
        messageKey,
        null,
        payload,
        payloadType,
        headersJson,
        aggregateType,
        aggregateId,
        createdAtUnixMs);
    }

    public OutboxRecord(
        String eventId,
        String topic,
        String messageKey,
        Integer kafkaPartitionId,
            byte[] payload,
            String payloadType,
            String headersJson,
            String aggregateType,
            String aggregateId,
            long createdAtUnixMs) {
        this.eventId = Objects.requireNonNull(eventId);
        this.topic = Objects.requireNonNull(topic);
        this.messageKey = Objects.requireNonNull(messageKey);
        this.kafkaPartitionId = kafkaPartitionId;
        this.payload = Arrays.copyOf(Objects.requireNonNull(payload), payload.length);
        this.payloadType = Objects.requireNonNull(payloadType);
        this.headersJson = Objects.requireNonNull(headersJson);
        this.aggregateType = Objects.requireNonNull(aggregateType);
        this.aggregateId = Objects.requireNonNull(aggregateId);
        this.createdAtUnixMs = createdAtUnixMs;
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