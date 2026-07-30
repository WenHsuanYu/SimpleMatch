package com.simplematch.quickfixgateway.config;

import java.nio.file.Path;

/** Resolves the immutable runtime paths and owner identity of a gateway instance. */
public record QuickFixGatewayRuntime(
    String env, Path quickfixConfigPath, Path walPath, String ownerId) {
  /** Creates a runtime descriptor with the default single-instance owner identity. */
  public QuickFixGatewayRuntime(String env, Path quickfixConfigPath, Path walPath) {
    this(env, quickfixConfigPath, walPath, "quickfix-gateway-0");
  }
}
