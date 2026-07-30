package com.simplematch.persistence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boots the rebuildable projection service. */
@SpringBootApplication
public class PersistenceApplication {
  /** Starts the persistence service. */
  public static void main(String[] args) {
    SpringApplication.run(PersistenceApplication.class, args);
  }
}
