package com.simplematch.quickfixgateway.fix;

import quickfix.Log;
import quickfix.LogFactory;
import quickfix.SessionID;

/**
 * Protects all QuickFIX log channels, including malformed messages logged before authentication.
 */
public final class CredentialRedactingFixLogFactory implements LogFactory {
  private final LogFactory delegate;

  /**
   * Wraps the gateway's existing log destination without retaining credential-bearing text.
   *
   * @param delegate existing QuickFIX log destination
   */
  public CredentialRedactingFixLogFactory(LogFactory delegate) {
    this.delegate = delegate;
  }

  /** Creates a log that removes credential-bearing entries before reaching its destination. */
  @Override
  public Log create(SessionID sessionId) {
    return new RedactingLog(delegate.create(sessionId));
  }

  private record RedactingLog(Log delegate) implements Log {
    @Override
    public void clear() {
      delegate.clear();
    }

    @Override
    public void onIncoming(String message) {
      delegate.onIncoming(redact(message));
    }

    @Override
    public void onOutgoing(String message) {
      delegate.onOutgoing(redact(message));
    }

    @Override
    public void onEvent(String message) {
      delegate.onEvent(redact(message));
    }

    @Override
    public void onErrorEvent(String message) {
      delegate.onErrorEvent(redact(message));
    }

    @Override
    public void onWarnEvent(String message) {
      delegate.onWarnEvent(redact(message));
    }

    private static String redact(String message) {
      // Drop the whole entry: malformed FIX and exception text may not retain field delimiters.
      return message.contains("553=") || message.contains("554=")
          ? "[FIX credential-bearing message redacted]"
          : message;
    }
  }
}
