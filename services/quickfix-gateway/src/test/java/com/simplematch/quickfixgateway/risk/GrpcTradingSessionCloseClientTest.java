package com.simplematch.quickfixgateway.risk;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.risk.v2.CloseTradingSessionRequest;
import com.simplematch.contracts.risk.v2.CloseTradingSessionResponse;
import com.simplematch.contracts.risk.v2.TradingSessionOperationsServiceGrpc;
import com.simplematch.quickfixgateway.operations.RetryableTradingSessionCloseException;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GrpcTradingSessionCloseClientTest {
  private static final String TRADING_SESSION_ID = "2026-08-11-regular";

  @Test
  void temporaryGrpcFailuresSurfaceAsRetryableCloseFailures() {
    for (Status.Code code :
        new Status.Code[] {
          Status.Code.ABORTED,
          Status.Code.DEADLINE_EXCEEDED,
          Status.Code.RESOURCE_EXHAUSTED,
          Status.Code.UNAVAILABLE
        }) {
      assertThatThrownBy(() -> closeAgainst(code))
          .isInstanceOf(RetryableTradingSessionCloseException.class);
    }
  }

  @Test
  void permanentGrpcFailuresRemainPermanent() {
    for (Status.Code code :
        new Status.Code[] {
          Status.Code.INTERNAL, Status.Code.INVALID_ARGUMENT, Status.Code.PERMISSION_DENIED
        }) {
      assertThatThrownBy(() -> closeAgainst(code))
          .isInstanceOf(StatusRuntimeException.class)
          .isNotInstanceOf(RetryableTradingSessionCloseException.class);
    }
  }

  @Test
  void successfulGrpcCloseReturnsNormally() {
    assertThatCode(() -> closeAgainst(Status.Code.OK)).doesNotThrowAnyException();
  }

  private static void closeAgainst(Status.Code code) throws IOException, InterruptedException {
    final Server server =
        ServerBuilder.forPort(0)
            .addService(
                new TradingSessionOperationsServiceGrpc.TradingSessionOperationsServiceImplBase() {
                  @Override
                  public void closeTradingSession(
                      CloseTradingSessionRequest request,
                      StreamObserver<CloseTradingSessionResponse> responseObserver) {
                    if (code != Status.Code.OK) {
                      responseObserver.onError(Status.fromCode(code).asRuntimeException());
                      return;
                    }
                    responseObserver.onNext(CloseTradingSessionResponse.getDefaultInstance());
                    responseObserver.onCompleted();
                  }
                })
            .build()
            .start();
    final ManagedChannel channel =
        ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
    try {
      new GrpcTradingSessionCloseClient(channel, 1_000L).close(TRADING_SESSION_ID);
    } finally {
      channel.shutdownNow();
      server.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
      server.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
