package com.simplematch.queryservice.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Owns private TLS and ACL material within a JUnit-managed temporary directory. */
record RedisTlsCertificates(Path directory, String password, String adminPassword) {
  /** Prepares every certificate before any Redis container can start. */
  static RedisTlsCertificates create(Path directory) throws IOException, InterruptedException {
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    final RedisTlsCertificates certificates =
        new RedisTlsCertificates(directory, randomPassword(), randomPassword());
    certificates.openssl(
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-sha256",
        "-days",
        "1",
        "-subj",
        "/CN=Query-Redis-Test-CA",
        "-keyout",
        "ca.key",
        "-out",
        "ca.crt");
    certificates.signEndpoint("trusted", "localhost");
    certificates.signEndpoint("mismatch", "wrong.example.invalid");
    certificates.request("untrusted", "localhost");
    certificates.openssl(
        "x509",
        "-req",
        "-sha256",
        "-days",
        "1",
        "-in",
        "untrusted.csr",
        "-signkey",
        "untrusted.key",
        "-extfile",
        "trusted.ext",
        "-out",
        "untrusted.crt");
    final Path acl = directory.resolve("users.acl");
    Files.createFile(
        acl, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(
        acl,
        "user default off\nuser query-cache on >"
            + certificates.password()
            + " ~query:v2:tls:* -@all +hello +ping +client|setinfo +get +set +del +scan\n"
            + "user test-admin on >"
            + certificates.adminPassword()
            + " ~* +@all\n");
    return certificates;
  }

  private void signEndpoint(String identity, String hostname)
      throws IOException, InterruptedException {
    request(identity, hostname);
    final String subjectAltName =
        hostname.equals("localhost") ? "DNS:localhost,IP:127.0.0.1" : "DNS:" + hostname;
    Files.writeString(
        directory.resolve(identity + ".ext"),
        "subjectAltName=" + subjectAltName + "\nextendedKeyUsage=serverAuth\n");
    openssl(
        "x509",
        "-req",
        "-sha256",
        "-days",
        "1",
        "-in",
        identity + ".csr",
        "-CA",
        "ca.crt",
        "-CAkey",
        "ca.key",
        "-CAcreateserial",
        "-extfile",
        identity + ".ext",
        "-out",
        identity + ".crt");
  }

  private void request(String identity, String hostname) throws IOException, InterruptedException {
    openssl(
        "req",
        "-new",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-subj",
        "/CN=" + hostname,
        "-keyout",
        identity + ".key",
        "-out",
        identity + ".csr");
  }

  /** Executes the certificate tool directly, with a private log and a deadline. */
  private void openssl(String... arguments) throws IOException, InterruptedException {
    final List<String> command = new ArrayList<>(List.of("openssl"));
    command.addAll(List.of(arguments));
    final Path log = directory.resolve("openssl.log");
    final Process process =
        new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
            .start();
    try {
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        throw preparationFailure("OpenSSL certificate preparation timed out", log);
      }
      if (process.exitValue() != 0) {
        throw preparationFailure(
            "OpenSSL certificate preparation failed (exit " + process.exitValue() + ")", log);
      }
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  /** Preserves bounded tool diagnostics after JUnit removes the private temporary files. */
  private IOException preparationFailure(String reason, Path log) throws IOException {
    try (InputStream output = Files.newInputStream(log)) {
      output.skipNBytes(Math.max(0, Files.size(log) - 4096));
      return new IOException(
          reason + ":\n" + new String(output.readNBytes(4096), StandardCharsets.UTF_8));
    }
  }

  private static String randomPassword() {
    final byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}
