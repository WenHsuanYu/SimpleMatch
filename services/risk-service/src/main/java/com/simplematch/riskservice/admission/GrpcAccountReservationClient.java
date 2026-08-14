package com.simplematch.riskservice.admission;

import com.simplematch.config.GrpcProperties;
import com.simplematch.contracts.account.v2.AccountLifecycleState;
import com.simplematch.contracts.account.v2.AccountReservationServiceGrpc;
import com.simplematch.contracts.account.v2.ReservationAction;
import com.simplematch.contracts.account.v2.ReservationCommand;
import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TwdNotional;
import com.simplematch.contracts.common.v2.TwdPrice;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.orders.v2.ShareQuantity;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Bridges risk admission to account reservation without holding a local transaction.
 *
 * <p>The admission application service invokes this adapter only between its local transactions.
 */
public final class GrpcAccountReservationClient implements AccountReservationClient, AutoCloseable {
  private final ManagedChannel channel;
  private final AccountReservationServiceGrpc.AccountReservationServiceBlockingStub account;

  /** Creates a local plaintext or production mTLS channel using the configured account target. */
  public GrpcAccountReservationClient(GrpcProperties properties) {
    this.channel = channel(properties);
    this.account = AccountReservationServiceGrpc.newBlockingStub(channel);
  }

  /** Calls account-service with the same UUID command identity used by the saga journal. */
  @Override
  public ReservationOutcome reserve(AdmissionCommand command) {
    final AdmissionIdentity identity = command.identity();
    final AdmissionOrder order = command.order();
    final AdmissionOrder.Characteristics characteristics = order.characteristics();
    final Long limitPriceUnits = characteristics.limitPrice().value();
    final String commandId = identity.commandId().value().toString();
    final String orderId = identity.orderId().value().toString();
    final ReservationCommand request =
        ReservationCommand.newBuilder()
            .setMetadata(
                EventMetadata.newBuilder()
                    .setSchemaVersion("v2")
                    .setEventId(commandId)
                    .setCreatedAtUnixMs(System.currentTimeMillis())
                    .setSourceService("risk-service")
                    .setCorrelationId(commandId)
                    .build())
            .setReservationId(orderId)
            .setCommandId(commandId)
            .setOrderId(orderId)
            .setAccountId(identity.accountId().value().toString())
            .setAction(ReservationAction.RESERVATION_ACTION_RESERVE)
            .setInstrument(
                VenueInstrument.newBuilder()
                    .setSymbol(order.instrument().symbol().value())
                    .setVenueMic(order.instrument().venueMic().value())
                    .build())
            .setSide(side(characteristics.side().value()))
            .setQuantity(
                ShareQuantity.newBuilder().setShares(characteristics.quantity().value()).build())
            .setLimitPrice(
                TwdPrice.newBuilder()
                    .setUnits(limitPriceUnits == null ? 0L : limitPriceUnits)
                    .build())
            .setNotional(
                TwdNotional.newBuilder()
                    .setUnits(notionalUnits(limitPriceUnits, characteristics.quantity().value()))
                    .build())
            .build();
    final var response = reserve(request);
    if (response.getState() == AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_RESERVED
        || response.getState() == AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_FILLED) {
      return ReservationOutcome.accepted(UUID.fromString(response.getReservationId()));
    }
    return ReservationOutcome.rejected(response.getReasonCode(), response.getReasonDetail());
  }

  private com.simplematch.contracts.account.v2.AccountLifecycleEvent reserve(
      ReservationCommand request) {
    try {
      return account.withDeadlineAfter(2, TimeUnit.SECONDS).reserve(request);
    } catch (StatusRuntimeException failure) {
      throw translate(failure);
    }
  }

  private RuntimeException translate(StatusRuntimeException failure) {
    return switch (Status.fromThrowable(failure).getCode()) {
      case ALREADY_EXISTS -> new AdmissionConflictException();
      case INVALID_ARGUMENT ->
          new AdmissionValidationException(
              AdmissionFailure.invalidCommand(accountDescription(failure)));
      case FAILED_PRECONDITION -> new AdmissionInvariantException(failure);
      case DEADLINE_EXCEEDED, UNAVAILABLE, RESOURCE_EXHAUSTED ->
          new AdmissionUnavailableException(failure);
      default -> new AdmissionAccountFailureException(failure);
    };
  }

  private String accountDescription(StatusRuntimeException failure) {
    final String description = Status.fromThrowable(failure).getDescription();
    return description == null || description.isBlank()
        ? "account reservation command was invalid"
        : "account reservation command was invalid: " + description;
  }

  /** Closes the account gRPC channel. */
  @Override
  public void close() {
    channel.shutdown();
  }

  private Side side(String value) {
    return switch (value) {
      case "SIDE_BUY", "BUY" -> Side.SIDE_BUY;
      case "SIDE_SELL", "SELL" -> Side.SIDE_SELL;
      default -> throw new IllegalArgumentException("unsupported admission side: " + value);
    };
  }

  private long notionalUnits(Long priceUnits, long quantity) {
    if (priceUnits == null) {
      return 0L;
    }
    try {
      return Math.multiplyExact(priceUnits, quantity);
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException(
          "reservation notional exceeds fixed-point range", overflow);
    }
  }

  private ManagedChannel channel(GrpcProperties properties) {
    final String target = properties.targets().accountService();
    final GrpcProperties.SecurityProperties security = properties.security();
    if (!security.tlsEnabled()) {
      return ManagedChannelBuilder.forTarget(target).usePlaintext().build();
    }
    try {
      return NettyChannelBuilder.forTarget(target)
          .sslContext(
              GrpcSslContexts.forClient()
                  .trustManager(new File(security.trustCertificatePath()))
                  .keyManager(
                      new File(security.certificatePath()), new File(security.privateKeyPath()))
                  .build())
          .useTransportSecurity()
          .build();
    } catch (IOException failure) {
      throw new IllegalStateException("failed to configure account-service gRPC TLS", failure);
    }
  }
}
