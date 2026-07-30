package com.simplematch.quickfixgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the QuickFIX gateway Spring Boot application. */
@SpringBootApplication
public class QuickFixGatewayApplication {
  /** Starts the application with the supplied command-line arguments. */
  public static void main(String[] args) {
    SpringApplication.run(QuickFixGatewayApplication.class, args);
  }
}
