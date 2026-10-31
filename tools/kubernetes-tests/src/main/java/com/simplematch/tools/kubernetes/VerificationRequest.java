package com.simplematch.tools.kubernetes;

import java.nio.file.Path;

/**
 * Selects fresh evidence, shared coordination, and explicit local retention.
 *
 * @param evidence new evidence destination
 * @param coordination directory shared by all worktrees using this environment
 * @param retain whether the caller explicitly requests local resource retention
 */
public record VerificationRequest(Path evidence, Path coordination, boolean retain) {}
