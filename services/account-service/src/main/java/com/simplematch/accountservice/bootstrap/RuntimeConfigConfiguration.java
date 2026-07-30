package com.simplematch.accountservice.bootstrap;

import com.simplematch.config.PlatformProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RuntimeConfigConfiguration {
  @Bean
  AccountServiceRuntime accountServiceRuntime(PlatformProperties properties) {
    return AccountServiceRuntime.from(properties);
  }
}
