package com.simplematch.accountservice.config;

import com.simplematch.config.SimpleMatchDataSourceSettings;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configures account-service persistence infrastructure. */
@Configuration
public class AccountPersistenceConfiguration {
  private static final String ACCOUNT_SERVICE_SCHEMA = "account_service";

  @Bean
  Clock accountServiceClock() {
    return Clock.systemUTC();
  }

  @Bean
  SimpleMatchDataSourceSettings accountServiceDataSourceSettings() {
    return new SimpleMatchDataSourceSettings(ACCOUNT_SERVICE_SCHEMA, 4, "account-service-hikari");
  }
}
