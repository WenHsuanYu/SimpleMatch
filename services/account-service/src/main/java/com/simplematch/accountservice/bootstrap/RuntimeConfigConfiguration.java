package com.simplematch.accountservice.bootstrap;

import com.simplematch.config.GrpcProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configures runtime values derived from platform settings. */
@Configuration
public class RuntimeConfigConfiguration {
  @Bean
  AccountServiceRuntime accountServiceRuntime(GrpcProperties properties) {
    return AccountServiceRuntime.from(properties);
  }
}
