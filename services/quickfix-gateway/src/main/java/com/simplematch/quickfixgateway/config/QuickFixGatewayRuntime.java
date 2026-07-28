package com.simplematch.quickfixgateway.config;

import java.nio.file.Path;

public record QuickFixGatewayRuntime(String env, Path quickfixConfigPath, Path walPath, String ownerId) {
    public QuickFixGatewayRuntime(String env, Path quickfixConfigPath, Path walPath) {
        this(env, quickfixConfigPath, walPath, "quickfix-gateway-0");
    }
}