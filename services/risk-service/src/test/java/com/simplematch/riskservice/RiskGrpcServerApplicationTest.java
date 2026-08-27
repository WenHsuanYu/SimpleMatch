package com.simplematch.riskservice;

import static com.simplematch.riskservice.testsupport.H2TestDatabaseUrl.riskServiceUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.risk.v2.CloseTradingSessionRequest;
import com.simplematch.contracts.risk.v2.TradingSessionOperationsServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.SmartLifecycle;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    properties = {
      "simplematch.risk-service.grpc.enabled=true",
      "simplematch.risk-service.scheduling-enabled=false",
      "simplematch.risk-service.market-reference.artifact-location=classpath:/market-reference/market_reference.json",
      "simplematch.risk-service.market-reference.checksum-location=classpath:/market-reference/market_reference.sha256",
      "simplematch.risk-service.market-reference.trading-day=2026-08-11",
      "simplematch.risk-service.market-reference.matching-image-digest=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "spring.main.web-application-type=none"
    })
@ActiveProfiles("test")
class RiskGrpcServerApplicationTest {
  private static final int SERVER_PORT = freePort();

  @Autowired
  @Qualifier("grpcServerLifecycle")
  private SmartLifecycle grpcServerLifecycle;

  @DynamicPropertySource
  static void configureDataSource(DynamicPropertyRegistry registry) {
    registry.add("simplematch.postgres.dsn", () -> riskServiceUrl("riskgrpc"));
    registry.add(
        "simplematch.grpc.targets.risk-service", () -> "dns:///127.0.0.1:" + SERVER_PORT);
  }

  @Test
  void startsProductionV2GrpcServerWithoutLegacyAdmissionService() {
    assertThat(grpcServerLifecycle.isRunning()).isTrue();
  }

  @Test
  void registersTradingSessionOperationsOnTheProductionGrpcServer() throws Exception {
    final ManagedChannel channel =
        ManagedChannelBuilder.forAddress("127.0.0.1", SERVER_PORT).usePlaintext().build();
    try {
      assertThatThrownBy(
              () ->
                  TradingSessionOperationsServiceGrpc.newBlockingStub(channel)
                      .closeTradingSession(
                          CloseTradingSessionRequest.newBuilder()
                              .setTradingSessionId("2026-08-12-regular")
                              .build()))
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(failure -> Status.fromThrowable(failure).getCode())
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
    } finally {
      channel.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException failure) {
      throw new IllegalStateException("failed to reserve a test gRPC port", failure);
    }
  }
}
