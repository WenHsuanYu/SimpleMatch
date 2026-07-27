package com.simplematch.quickfixgateway;

import com.simplematch.config.SimpleMatchConfig;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@Log4j2
@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = SimpleMatchConfig.class)
public class QuickFixGatewayApplication {
  static void main(String[] args) {

    var context = SpringApplication.run(QuickFixGatewayApplication.class, args);

    SimpleMatchConfig config = context.getBean(SimpleMatchConfig.class);
    var fixgw = config.getQuickfixGateway();

    var path = fixgw.getQuickfixConfigPath();
    log.info(path);
  }
}