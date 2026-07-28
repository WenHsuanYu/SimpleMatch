package com.simplematch.riskservice.submission;

import com.simplematch.riskservice.outbox.OutboxRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxRecordTest {
    @Test
    void eventInfoRejectsBlankEventId() {
        assertThatThrownBy(() -> new OutboxRecord.EventInfo("   ", 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId must not be blank");
    }

    @Test
    void eventInfoRejectsNegativeTimestamp() {
        assertThatThrownBy(() -> new OutboxRecord.EventInfo("event-1", -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("createdAtUnixMs must be >= 0");
    }

    @Test
    void routingRejectsBlankTopic() {
        assertThatThrownBy(() -> OutboxRecord.Routing.withPartition(" ", "AAPL", 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic must not be blank");
    }

    @Test
    void routingRejectsBlankMessageKey() {
        assertThatThrownBy(() -> OutboxRecord.Routing.withoutPartition("orders.validated", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageKey must not be blank");
    }

    @Test
    void routingRejectsNegativePartition() {
        assertThatThrownBy(() -> OutboxRecord.Routing.withPartition("orders.validated", "AAPL", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kafkaPartitionId must be >= 0");
    }

    @Test
    void payloadEnvelopeRejectsBlankPayloadType() {
        assertThatThrownBy(() -> new OutboxRecord.PayloadEnvelope(new byte[]{1}, " ", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloadType must not be blank");
    }

    @Test
    void payloadEnvelopeRejectsBlankHeadersJson() {
        assertThatThrownBy(() -> new OutboxRecord.PayloadEnvelope(new byte[]{1}, "type", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("headersJson must not be blank");
    }

    @Test
    void payloadEnvelopeDefensivelyCopiesInputAndAccessorPayload() {
        final byte[] payload = new byte[]{1, 2, 3};
        final OutboxRecord.PayloadEnvelope payloadEnvelope =
                new OutboxRecord.PayloadEnvelope(payload, "type", "{\"event_id\":\"event-1\"}");

        payload[0] = 9;
        final byte[] accessorPayload = payloadEnvelope.payload();
        accessorPayload[1] = 8;

        assertThat(payloadEnvelope.payload()).containsExactly(1, 2, 3);
    }

    @Test
    void aggregateRefRejectsBlankAggregateType() {
        assertThatThrownBy(() -> new OutboxRecord.AggregateRef(" ", "O-C1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregateType must not be blank");
    }

    @Test
    void aggregateRefRejectsNullAggregateId() {
        assertThatThrownBy(() -> new OutboxRecord.AggregateRef("risk_submission", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("aggregateId");
    }
}