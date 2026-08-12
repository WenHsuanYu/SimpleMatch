package com.simplematch.marketdatastreamer.config;

import com.simplematch.config.GrpcProperties;
import com.simplematch.marketdatastreamer.stream.MarketDataGrpcService;
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

/** Configures the market-data stream's gRPC server lifecycle. */
@Configuration(proxyBeanMethods = false)
public class MarketdataStreamerGrpcServerConfiguration {
  /** Starts the public stream server only when the service is enabled. */
  @Bean
  @ConditionalOnProperty(
      name = "simplematch.marketdata-streamer.enabled",
      havingValue = "true",
      matchIfMissing = false)
  SmartLifecycle marketdataStreamerGrpcServer(
      MarketdataStreamerProperties properties,
      GrpcProperties grpcProperties,
      MarketDataGrpcService service) {
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
              serverBuilder(properties.grpcPort(), grpcProperties)
                  .addService((BindableService) service)
                  .build()
                  .start();
          running = true;
        } catch (IOException failure) {
          throw new IllegalStateException("failed to start market-data gRPC server", failure);
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
