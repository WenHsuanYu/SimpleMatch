package com.simplematch.accountservice.bootstrap;

import com.simplematch.config.SimpleMatchConfig;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record AccountServiceRuntime(SimpleMatchConfig config, int grpcPort) {
  private static final Pattern PORT_PATTERN = Pattern.compile(":(\\d+)$");

  public static AccountServiceRuntime from(SimpleMatchConfig config) {
    final String target = config.getGrpc().getTargets().getAccountService();
    final Matcher matcher = PORT_PATTERN.matcher(target);
    if (matcher.find()) {
      return new AccountServiceRuntime(config, Integer.parseInt(matcher.group(1)));
    }
    return new AccountServiceRuntime(config, 50051);
  }
}