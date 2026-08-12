package com.simplematch.marketdatastreamer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the rebuildable public market-data streaming service. */
@SpringBootApplication
public class MarketdataStreamerApplication {
  private MarketdataStreamerApplication() {}

  /** Starts the market-data streamer process. */
  public static void main(String[] args) {
    SpringApplication.run(MarketdataStreamerApplication.class, args);
  }
}
