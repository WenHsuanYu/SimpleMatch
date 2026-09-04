package com.simplematch.riskservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AbstractOutboxEventFactoryTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void supportsMultipleAggregateFactoriesWithSharedAbstraction() {
    final OutboxEventFactory<OrderAggregate> orderFactory =
        new OrderAggregateOutboxFactory(objectMapper);
    final OutboxEventFactory<ReservationAggregate> reservationFactory =
        new ReservationAggregateOutboxFactory(objectMapper);

    final OutboxRecord orderRecord = orderFactory.create(new OrderAggregate("ORD-1", 100L));
    final OutboxRecord reservationRecord =
        reservationFactory.create(new ReservationAggregate("RSV-1", 200L));

    assertThat(orderRecord.aggregateReference().aggregateType()).isEqualTo("order");
    assertThat(orderRecord.aggregateReference().aggregateId()).isEqualTo("ORD-1");
    assertThat(orderRecord.routing().topic()).isEqualTo("matching.commands");
    assertThat(orderRecord.payloadEnvelope().payloadType()).isEqualTo("test.OrderPayload");

    assertThat(reservationRecord.aggregateReference().aggregateType()).isEqualTo("reservation");
    assertThat(reservationRecord.aggregateReference().aggregateId()).isEqualTo("RSV-1");
    assertThat(reservationRecord.routing().topic()).isEqualTo("reservations.created");
    assertThat(reservationRecord.payloadEnvelope().payloadType())
        .isEqualTo("test.ReservationPayload");
  }

  @Test
  void keepsSerializedPayloadBytesOwnedByTheEventDescriptor() {
    final byte[] sourceBytes = new byte[] {1, 2, 3};
    final AbstractOutboxEventFactory.SerializedPayload payload =
        new AbstractOutboxEventFactory.SerializedPayload(sourceBytes, "test.Payload");

    sourceBytes[0] = 9;
    final byte[] accessedBytes = payload.bytes();
    accessedBytes[1] = 8;

    assertThat(payload.bytes()).containsExactly(1, 2, 3);
  }

  private record OrderAggregate(String orderId, long createdAtUnixMs) {}

  private record ReservationAggregate(String reservationId, long createdAtUnixMs) {}

  private static final class OrderAggregateOutboxFactory
      extends AbstractOutboxEventFactory<OrderAggregate> {
    private OrderAggregateOutboxFactory(ObjectMapper objectMapper) {
      super(objectMapper, "application/x-protobuf");
    }

    @Override
    protected OutboxEvent buildEvent(OrderAggregate source) {
      return new OutboxEvent(
          new OutboxRecord.EventInfo("evt-order-1", source.createdAtUnixMs()),
          OutboxRecord.Routing.withPartition("matching.commands", source.orderId(), 0),
          new SerializedPayload(
              ("order:" + source.orderId()).getBytes(StandardCharsets.UTF_8),
              "test.OrderPayload"),
          new OutboxRecord.AggregateRef("order", source.orderId()));
    }
  }

  private static final class ReservationAggregateOutboxFactory
      extends AbstractOutboxEventFactory<ReservationAggregate> {
    private ReservationAggregateOutboxFactory(ObjectMapper objectMapper) {
      super(objectMapper, "application/x-protobuf");
    }

    @Override
    protected OutboxEvent buildEvent(ReservationAggregate source) {
      return new OutboxEvent(
          new OutboxRecord.EventInfo("evt-reservation-1", source.createdAtUnixMs()),
          OutboxRecord.Routing.withPartition(
              "reservations.created", source.reservationId(), 1),
          new SerializedPayload(
              ("reservation:" + source.reservationId()).getBytes(StandardCharsets.UTF_8),
              "test.ReservationPayload"),
          new OutboxRecord.AggregateRef("reservation", source.reservationId()));
    }
  }
}
