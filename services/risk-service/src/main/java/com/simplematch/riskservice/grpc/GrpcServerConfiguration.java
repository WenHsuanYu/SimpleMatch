package com.simplematch.riskservice.grpc;

import com.simplematch.config.GrpcProperties;
import com.simplematch.riskservice.bootstrap.RiskServiceRuntime;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.io.File;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configures the production gRPC lifecycle for the durable v2 admission API. */
@Configuration
public class GrpcServerConfiguration {
  @Bean
  @ConditionalOnProperty(
      name = "simplematch.risk-service.grpc.enabled",
      havingValue = "true",
      matchIfMissing = true)
  SmartLifecycle grpcServerLifecycle(
      RiskServiceRuntime runtime,
      GrpcProperties grpcProperties,
      OrderAdmissionGrpcService admissionService,
      TradingSessionOperationsGrpcService tradingSessionOperationsService) {
    return new SmartLifecycle() {
      private Server server;
      private volatile boolean running;

      @Override
      public void start() {
        if (running) {
          return;
        }
        try {
          final ServerBuilder<?> builder = serverBuilder(runtime.grpcPort(), grpcProperties);
          server =
              builder
                  .addService((BindableService) admissionService)
                  .addService((BindableService) tradingSessionOperationsService)
                  .build()
                  .start();
          running = true;
        } catch (IOException e) {
          throw new IllegalStateException("failed to start gRPC server", e);
        }
      }

      @Override
      public void stop() {
        if (server != null) {
          server.shutdown();
        }
        running = false;
      }

      @Override
      public boolean isRunning() {
        return running;
      }
    };
  }

  private ServerBuilder<?> serverBuilder(int port, GrpcProperties properties) throws IOException {
    final GrpcProperties.SecurityProperties security = properties.security();
    if (!security.tlsEnabled()) {
      return ServerBuilder.forPort(port);
    }
    return NettyServerBuilder.forPort(port)
        .sslContext(
            GrpcSslContexts.forServer(
                    new File(security.certificatePath()), new File(security.privateKeyPath()))
                .trustManager(new File(security.trustCertificatePath()))
                .clientAuth(ClientAuth.REQUIRE)
                .build());
  }
}
