package com.simplematch.quickfixgateway;

import com.simplematch.config.SimpleMatchConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = SimpleMatchConfig.class)
public class QuickFixGatewayApplication {
  public static void main(String[] args) {
    SpringApplication.run(QuickFixGatewayApplication.class, args);
  }
}