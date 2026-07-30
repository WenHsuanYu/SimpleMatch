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

    assertThat(orderRecord.aggregateType()).isEqualTo("order");
    assertThat(orderRecord.aggregateId()).isEqualTo("ORD-1");
    assertThat(orderRecord.topic()).isEqualTo("orders.validated");
    assertThat(orderRecord.payloadType()).isEqualTo("test.OrderPayload");

    assertThat(reservationRecord.aggregateType()).isEqualTo("reservation");
    assertThat(reservationRecord.aggregateId()).isEqualTo("RSV-1");
    assertThat(reservationRecord.topic()).isEqualTo("reservations.created");
    assertThat(reservationRecord.payloadType()).isEqualTo("test.ReservationPayload");
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
          "evt-order-1",
          source.createdAtUnixMs(),
          "orders.validated",
          source.orderId(),
          0,
          ("order:" + source.orderId()).getBytes(StandardCharsets.UTF_8),
          "test.OrderPayload",
          "order",
          source.orderId());
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
          "evt-reservation-1",
          source.createdAtUnixMs(),
          "reservations.created",
          source.reservationId(),
          1,
          ("reservation:" + source.reservationId()).getBytes(StandardCharsets.UTF_8),
          "test.ReservationPayload",
          "reservation",
          source.reservationId());
    }
  }
}
