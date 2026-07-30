package com.simplematch.riskservice.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables risk-service background recovery when its operational switch is enabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "simplematch.risk-service.scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableScheduling
class RiskServiceSchedulingConfiguration {}
