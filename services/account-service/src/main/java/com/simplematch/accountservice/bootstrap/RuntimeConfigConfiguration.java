package com.simplematch.accountservice.bootstrap;

import com.simplematch.config.SimpleMatchConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RuntimeConfigConfiguration {
  @Bean
  AccountServiceRuntime accountServiceRuntime(SimpleMatchConfig simpleMatchConfig) {
    return AccountServiceRuntime.from(simpleMatchConfig);
  }
}