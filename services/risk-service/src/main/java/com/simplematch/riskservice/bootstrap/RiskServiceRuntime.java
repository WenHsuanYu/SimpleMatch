package com.simplematch.riskservice.bootstrap;

import com.simplematch.config.PlatformProperties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record RiskServiceRuntime(int grpcPort) {
  private static final Pattern PORT_PATTERN = Pattern.compile(":(\\d+)$");

  public static RiskServiceRuntime from(PlatformProperties properties) {
    final String target = properties.grpc().targets().riskService();
    final Matcher matcher = PORT_PATTERN.matcher(target);
    final int grpcPort = matcher.find() ? Integer.parseInt(matcher.group(1)) : 50052;
    return new RiskServiceRuntime(grpcPort);
  }
}
