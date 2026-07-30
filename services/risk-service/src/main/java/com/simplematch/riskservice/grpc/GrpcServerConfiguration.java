package com.simplematch.riskservice.grpc;

import com.simplematch.riskservice.bootstrap.RiskServiceRuntime;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcServerConfiguration {
  @Bean
  @ConditionalOnProperty(
      name = "simplematch.risk-service.grpc.enabled",
      havingValue = "true",
      matchIfMissing = true)
  SmartLifecycle grpcServerLifecycle(
      RiskServiceRuntime runtime,
      RiskGrpcService legacyService,
      OrderAdmissionGrpcService admissionService) {
    return new SmartLifecycle() {
      private Server server;
      private volatile boolean running;

      @Override
      public void start() {
        if (running) {
          return;
        }
        try {
          server =
              ServerBuilder.forPort(runtime.grpcPort())
                  .addService((BindableService) legacyService)
                  .addService((BindableService) admissionService)
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
}
