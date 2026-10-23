package com.simplematch.queryservice.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Accepts exactly one explicit Bearer header, without cookie, query-string or identity fallback.
 */
final class QueryBearerAuthenticationConverter implements AuthenticationConverter {
  private static final Pattern BEARER =
      Pattern.compile("Bearer +([A-Za-z0-9_-]{43})", Pattern.CASE_INSENSITIVE);

  /**
   * Rejects ambiguous credentials before any protected controller or UUID conversion is reached.
   */
  @Override
  public Authentication convert(HttpServletRequest request) {
    final Enumeration<String> headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
    if (!headers.hasMoreElements()) {
      return null;
    }
    final String supplied = headers.nextElement();
    if (supplied == null || supplied.length() > 128 || headers.hasMoreElements()) {
      throw new BadCredentialsException("Query credential is invalid");
    }
    final Matcher match = BEARER.matcher(supplied);
    if (!match.matches()) {
      throw new BadCredentialsException("Query credential is invalid");
    }
    return UsernamePasswordAuthenticationToken.unauthenticated("query", match.group(1));
  }
}
