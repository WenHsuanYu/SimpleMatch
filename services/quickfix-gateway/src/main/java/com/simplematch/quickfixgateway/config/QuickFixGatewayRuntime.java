package com.simplematch.quickfixgateway.config;

import java.nio.file.Path;

public record QuickFixGatewayRuntime(String env, Path quickfixConfigPath, Path walPath) {
}