package com.simplematch.tools.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Keeps linked observations inside one directory without following symlinks. */
final class EvidenceFiles {
  private final Path directory;

  EvidenceFiles(Path directory) {
    this.directory = withoutSymlinks(directory);
    ReportFields.require(Files.isDirectory(this.directory), "Missing evidence directory.");
  }

  static EvidenceFiles forReport(Path report) {
    final Path parent = safeFile(report).getParent();
    if (parent == null) {
      throw new IllegalArgumentException("Report directory is missing.");
    }
    return new EvidenceFiles(parent);
  }

  Path file(String relative) {
    final Path path = Path.of(relative);
    ReportFields.require(!relative.isBlank() && !path.isAbsolute(), "Expected a relative file.");
    for (Path part : path) {
      ReportFields.require(!part.toString().equals(".."), "Evidence path escapes its directory.");
    }
    final Path resolved = safeFile(directory.resolve(path));
    ReportFields.require(resolved.startsWith(directory), "Evidence path escapes its directory.");
    return resolved;
  }

  JsonNode json(String relative) throws IOException {
    return JsonReports.read(file(relative));
  }

  JsonNode checked(String relative, String digest) throws IOException {
    final Path path = file(relative);
    ReportFields.require(sha256(path).equals(digest), "Changed evidence: " + relative);
    return JsonReports.read(path);
  }

  static Path safeFile(Path path) {
    final Path safe = withoutSymlinks(path);
    ReportFields.require(
        Files.isRegularFile(safe, LinkOption.NOFOLLOW_LINKS), "Missing evidence file: " + path);
    return safe;
  }

  /**
   * Validates a new report destination without following a linked parent or replacing a directory.
   */
  static Path outputFile(Path path) {
    final Path safe = withoutSymlinks(path);
    final Path parent = safe.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("Missing evidence destination directory.");
    }
    ReportFields.require(Files.isDirectory(parent), "Missing evidence destination directory.");
    ReportFields.require(
        !Files.exists(safe) || Files.isRegularFile(safe, LinkOption.NOFOLLOW_LINKS),
        "Invalid evidence destination.");
    return safe;
  }

  static String sha256(Path path) throws IOException {
    return "sha256:" + bytesSha256(Files.readAllBytes(safeFile(path)));
  }

  static String bytesSha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable.", failure);
    }
  }

  private static Path withoutSymlinks(Path path) {
    final Path absolute = path.toAbsolutePath();
    Path current = absolute.getRoot();
    for (Path part : absolute) {
      current = current.resolve(part);
      ReportFields.require(!Files.isSymbolicLink(current), "Symlinked evidence path: " + current);
    }
    return absolute.normalize();
  }
}
