package com.simplematch.riskservice.bootstrap;

import com.simplematch.config.SimpleMatchConfig;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record RiskServiceRuntime(SimpleMatchConfig config, int grpcPort) {
  private static final Pattern PORT_PATTERN = Pattern.compile(":(\\d+)$");

  public static RiskServiceRuntime from(SimpleMatchConfig config) {
    final String target = config.getGrpc().getTargets().getRiskService();
    final Matcher matcher = PORT_PATTERN.matcher(target);
    final int grpcPort = matcher.find() ? Integer.parseInt(matcher.group(1)) : 50052;
    return new RiskServiceRuntime(config, grpcPort);
  }
}