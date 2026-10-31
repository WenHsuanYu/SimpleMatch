package com.simplematch.tools.kubernetes;

import java.util.regex.Pattern;

/** Redacts credentials before captured command diagnostics leave the process adapter. */
final class DiagnosticText {
  private static final Pattern URI_USER = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@]+@");
  private static final Pattern PRIVATE_FIELD = Pattern.compile(
      "(?i)(password|passwd|authorization|"
          + "token|access_token|refresh_token|client_secret|"
          + "client-key-data|client-certificate-data)"
          + "[\"']?\\s*[:=]\\s*(?:Bearer\\s+)?"
          + "(?:\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[^\\s,;]+)");

  private DiagnosticText() {}

  static String failure(Throwable failure) {
    if (failure == null) {
      return "";
    }
    final String safe = redact(failure.getClass().getName() + ": " + failure.getMessage());
    return safe.length() <= 4_000 ? safe : safe.substring(0, 1_000)
        + "\n[intermediate diagnostic omitted]\n" + safe.substring(safe.length() - 3_000);
  }

  static String redact(String text) {
    final String withoutUser = URI_USER.matcher(text).replaceAll("$1<REDACTED>@");
    return PRIVATE_FIELD.matcher(withoutUser).replaceAll("$1=<REDACTED>");
  }
}
