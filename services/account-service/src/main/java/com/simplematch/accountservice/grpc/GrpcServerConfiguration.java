package com.simplematch.accountservice.grpc;

import com.simplematch.accountservice.bootstrap.AccountServiceRuntime;
import com.simplematch.config.GrpcProperties;
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

/** Configures the account-service gRPC server lifecycle. */
@Configuration
public class GrpcServerConfiguration {
  @Bean
  @ConditionalOnProperty(
      name = "simplematch.account-service.grpc.enabled",
      havingValue = "true",
      matchIfMissing = true)
  SmartLifecycle grpcServerLifecycle(
      AccountServiceRuntime runtime,
      GrpcProperties grpcProperties,
      AccountGrpcService service,
      AccountReservationV2GrpcService v2Service) {
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
                  .addService((BindableService) service)
                  .addService((BindableService) v2Service)
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
