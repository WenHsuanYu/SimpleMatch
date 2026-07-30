package com.simplematch.riskservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the risk-service Spring Boot application. */
@SpringBootApplication
public class RiskServiceApplication {
  /** Starts risk-service with the supplied command-line arguments. */
  public static void main(String[] args) {
    SpringApplication.run(RiskServiceApplication.class, args);
  }
}
