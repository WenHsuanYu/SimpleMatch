package com.simplematch.config;

import java.nio.file.Path;
import java.util.Optional;

public record LoadedSimpleMatchConfig(SimpleMatchConfig config, Optional<Path> appConfigPath) {
}