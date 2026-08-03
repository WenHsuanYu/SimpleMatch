package com.simplematch.accountservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the account-service Spring application. */
@SpringBootApplication
public class AccountServiceApplication {
  private AccountServiceApplication() {}

  /** Starts the account-service process. */
  public static void main(String[] args) {
    SpringApplication.run(AccountServiceApplication.class, args);
  }
}
