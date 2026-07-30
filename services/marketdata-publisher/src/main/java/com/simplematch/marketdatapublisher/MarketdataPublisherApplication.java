package com.simplematch.marketdatapublisher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the market-reference publisher without consuming runtime trading events. */
@SpringBootApplication
public class MarketdataPublisherApplication {
  /** Starts the Spring Boot market-reference publisher process. */
  @SuppressWarnings("checkstyle:Indentation")
  public static void main(String[] args) {
    SpringApplication.run(MarketdataPublisherApplication.class, args);
  }
}
