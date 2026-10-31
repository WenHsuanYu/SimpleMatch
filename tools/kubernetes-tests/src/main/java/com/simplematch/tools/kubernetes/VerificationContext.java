package com.simplematch.tools.kubernetes;

import java.nio.file.Path;

/**
 * Identifies one live invocation without exposing its lease to scenario code.
 *
 * @param runId new invocation identity
 * @param evidence fresh evidence directory
 */
public record VerificationContext(String runId, Path evidence) {}
