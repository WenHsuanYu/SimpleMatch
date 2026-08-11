package com.simplematch.queryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boots the independently rebuildable read-side query service. */
@SpringBootApplication
public class QueryServiceApplication {
  private QueryServiceApplication() {}

  /** Starts the query service process. */
  public static void main(String[] args) {
    SpringApplication.run(QueryServiceApplication.class, args);
  }
}
