package com.simplematch.tools.kubernetes;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Owns one fresh evidence destination and the exclusive shared-environment lease. */
public final class VerificationWorkspace implements AutoCloseable {
  private final Path evidence;
  private final String runId;
  private final FileChannel lease;

  private VerificationWorkspace(Path evidence, String runId, FileChannel lease) {
    this.evidence = evidence;
    this.runId = runId;
    this.lease = lease;
  }

  /** Claims fresh evidence and a cross-process lease keyed by the observed environment identity. */
  public static VerificationWorkspace open(Path evidence, Path coordination, String environment)
      throws IOException {
    final Path destination = evidence.toAbsolutePath().normalize();
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException("verification evidence destination already exists");
    }
    final Path leases = coordination.toAbsolutePath().normalize();
    rejectSymlinks(destination);
    rejectSymlinks(leases);
    Files.createDirectories(leases);
    final FileChannel channel = FileChannel.open(leases.resolve(key(environment) + ".lock"),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    try {
      claimLease(channel);
      final String run = createEvidence(destination);
      return new VerificationWorkspace(destination, run, channel);
    } catch (IOException | RuntimeException | Error failure) {
      try {
        channel.close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  /** Returns this invocation's evidence directory, which remains after lease release. */
  public Path evidence() {
    return evidence;
  }

  /** Returns the newly generated run identity; prior runtime results are never adopted. */
  public String runId() {
    return runId;
  }

  /** Closes the channel, releasing its operating-system lease while preserving evidence. */
  @Override
  public void close() throws IOException {
    lease.close();
  }

  private static void claimLease(FileChannel channel) throws IOException {
    final FileLock lock;
    try {
      lock = channel.tryLock();
    } catch (OverlappingFileLockException conflict) {
      throw new IllegalStateException("another verification owns this environment", conflict);
    }
    if (lock == null) {
      throw new IllegalStateException("another verification owns this environment");
    }
  }

  private static String createEvidence(Path destination) throws IOException {
    final Path parent = destination.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("evidence destination must have a parent directory");
    }
    Files.createDirectories(parent);
    Files.createDirectory(destination);
    final String run = UUID.randomUUID().toString();
    Files.writeString(destination.resolve("run-id"), run, StandardOpenOption.CREATE_NEW);
    return run;
  }

  private static String key(String identity) {
    if (identity == null || identity.isBlank()) {
      throw new IllegalArgumentException("observed environment identity is required");
    }
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(identity.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void rejectSymlinks(Path path) {
    for (Path current = path; current != null; current = current.getParent()) {
      if (Files.isSymbolicLink(current)) {
        throw new IllegalArgumentException("verification paths cannot traverse symbolic links");
      }
    }
  }
}
