package com.simplematch.accountservice.bootstrap;

import com.simplematch.config.GrpcProperties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runtime values required to start the account service. */
public record AccountServiceRuntime(int grpcPort) {
  private static final Pattern PORT_PATTERN = Pattern.compile(":(\\d+)$");

  /**
   * Derives runtime values from the gRPC capability configuration.
   *
   * @param properties independently bound gRPC capability
   * @return account-service runtime values
   */
  public static AccountServiceRuntime from(GrpcProperties properties) {
    final String target = properties.targets().accountService();
    final Matcher matcher = PORT_PATTERN.matcher(target);
    if (matcher.find()) {
      return new AccountServiceRuntime(Integer.parseInt(matcher.group(1)));
    }
    return new AccountServiceRuntime(50051);
  }
}
