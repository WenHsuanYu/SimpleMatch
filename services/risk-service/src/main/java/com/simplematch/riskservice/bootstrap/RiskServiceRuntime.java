package com.simplematch.riskservice.bootstrap;

import com.simplematch.config.GrpcProperties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Holds the resolved risk-service runtime settings. */
public record RiskServiceRuntime(int grpcPort) {
  private static final Pattern PORT_PATTERN = Pattern.compile(":(\\d+)$");

  /**
   * Resolves the runtime settings from the gRPC capability configuration.
   *
   * @param properties independently bound gRPC capability
   * @return risk-service runtime values
   */
  public static RiskServiceRuntime from(GrpcProperties properties) {
    final String target = properties.targets().riskService();
    final Matcher matcher = PORT_PATTERN.matcher(target);
    final int grpcPort = matcher.find() ? Integer.parseInt(matcher.group(1)) : 50052;
    return new RiskServiceRuntime(grpcPort);
  }
}
