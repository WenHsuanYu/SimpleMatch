package com.simplematch.marketdataprojection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boots the non-critical, rebuildable runtime market-data projection. */
@SpringBootApplication
public class MarketDataProjectionApplication {
  private MarketDataProjectionApplication() {}

  /** Starts the projection runtime. */
  public static void main(String[] args) {
    SpringApplication.run(MarketDataProjectionApplication.class, args);
  }
}
