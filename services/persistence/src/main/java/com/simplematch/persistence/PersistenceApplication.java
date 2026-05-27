package com.simplematch.persistence;

import com.simplematch.config.SimpleMatchConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = SimpleMatchConfig.class)
public class PersistenceApplication {
  public static void main(String[] args) {
    SpringApplication.run(PersistenceApplication.class, args);
  }
}