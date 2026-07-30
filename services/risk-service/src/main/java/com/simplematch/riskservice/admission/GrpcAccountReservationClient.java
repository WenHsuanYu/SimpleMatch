package com.simplematch.riskservice.admission;

import com.simplematch.config.PlatformProperties;
import com.simplematch.contracts.account.v1.AccountServiceGrpc;
import com.simplematch.contracts.account.v1.ReserveRequest;
import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** gRPC adapter that keeps account RPCs outside risk database transactions. */
public final class GrpcAccountReservationClient implements AccountReservationClient, AutoCloseable {
  private final ManagedChannel channel;
  private final AccountServiceGrpc.AccountServiceBlockingStub account;

  /** Creates a plaintext channel using the configured account-service target. */
  public GrpcAccountReservationClient(PlatformProperties properties) {
    this.channel =
        ManagedChannelBuilder.forTarget(properties.grpc().targets().accountService())
            .usePlaintext()
            .build();
    this.account = AccountServiceGrpc.newBlockingStub(channel);
  }

  /** Calls account-service with the same UUID command identity used by the saga journal. */
  @Override
  public ReservationOutcome reserve(AdmissionCommand command) {
    final AdmissionIdentity identity = command.identity();
    final AdmissionOrder order = command.order();
    final AdmissionOrder.Characteristics characteristics = order.characteristics();
    final Long limitPriceUnits = characteristics.limitPrice().value();
    final ReserveRequest request =
        ReserveRequest.newBuilder()
            .setRequestId(identity.commandId().value().toString())
            .setOrderId(identity.orderId().value().toString())
            .setAccountId(identity.accountId().value().toString())
            .setSymbol(order.instrument().symbol().value())
            .setSide(
                characteristics.side().value().equals(Side.SIDE_SELL.name())
                    ? Side.SIDE_SELL
                    : Side.SIDE_BUY)
            .setQuantity(Long.toString(characteristics.quantity().value()))
            .setLimitPrice(limitPriceUnits == null ? "" : fixedPrice(limitPriceUnits))
            .build();
    final var response = account.withDeadlineAfter(2, TimeUnit.SECONDS).reserve(request);
    if (response.getStatus() == ReservationStatus.RESERVATION_STATUS_ACCEPTED) {
      return ReservationOutcome.accepted(UUID.fromString(response.getReservationId()));
    }
    return ReservationOutcome.rejected(response.getReasonCode(), response.getReasonText());
  }

  /** Closes the account gRPC channel. */
  @Override
  public void close() {
    channel.shutdown();
  }

  private String fixedPrice(long units) {
    return BigDecimal.valueOf(units)
        .movePointLeft(4)
        .setScale(4, RoundingMode.UNNECESSARY)
        .toPlainString();
  }
}
