package com.simplematch.config;

public record SimpleMatchConfigOverrides(
    String appConfigPath,
    String env,
    String quickfixConfigPath,
    String walPath) {
}