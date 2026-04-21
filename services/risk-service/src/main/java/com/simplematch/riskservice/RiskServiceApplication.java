package com.simplematch.riskservice;

import com.simplematch.config.SimpleMatchConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = SimpleMatchConfig.class)
public class RiskServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(RiskServiceApplication.class, args);
  }
}