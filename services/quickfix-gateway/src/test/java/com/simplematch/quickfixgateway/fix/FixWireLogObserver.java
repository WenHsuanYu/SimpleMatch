package com.simplematch.quickfixgateway.fix;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import quickfix.Log;
import quickfix.LogFactory;
import quickfix.SessionID;
import quickfix.field.ClOrdID;
import quickfix.field.ExecID;
import quickfix.field.MsgSeqNum;
import quickfix.field.MsgType;
import quickfix.field.PossDupFlag;
import quickfix.fix44.ExecutionReport;

/** Observes raw incoming FIX messages before QuickFIX/J sequence validation. */
final class FixWireLogObserver implements LogFactory {
  private static final char FIELD_DELIMITER = '\u0001';

  private final LogFactory delegate;
  private final LinkedBlockingQueue<String> incomingMessages = new LinkedBlockingQueue<>();

  FixWireLogObserver(LogFactory delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public Log create(SessionID sessionId) {
    return new ObservingLog(delegate.create(sessionId));
  }

  void discardIncoming() {
    incomingMessages.clear();
  }

  WireMessage awaitResentExecutionReport(
      String clOrdId, int messageSequence, String executionId, int timeoutSeconds)
      throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (System.nanoTime() < deadline) {
      final long remainingNanos = deadline - System.nanoTime();
      final String rawMessage =
          incomingMessages.poll(
              Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)),
              TimeUnit.MILLISECONDS);
      if (rawMessage == null) {
        break;
      }
      final WireMessage message = WireMessage.parse(rawMessage);
      if (ExecutionReport.MSGTYPE.equals(message.field(MsgType.FIELD))
          && clOrdId.equals(message.field(ClOrdID.FIELD))
          && Integer.toString(messageSequence).equals(message.field(MsgSeqNum.FIELD))
          && executionId.equals(message.field(ExecID.FIELD))
          && "Y".equals(message.field(PossDupFlag.FIELD))) {
        return message;
      }
    }
    throw new AssertionError(
        "expected raw FIX retransmission was not observed before the deadline");
  }

  record WireMessage(Map<Integer, String> fields) {
    WireMessage {
      fields = Map.copyOf(fields);
    }

    static WireMessage parse(String rawMessage) {
      final Map<Integer, String> fields = new HashMap<>();
      int fieldStart = 0;
      while (fieldStart < rawMessage.length()) {
        int fieldEnd = rawMessage.indexOf(FIELD_DELIMITER, fieldStart);
        if (fieldEnd < 0) {
          fieldEnd = rawMessage.length();
        }
        final int separator = rawMessage.indexOf('=', fieldStart);
        if (separator > fieldStart && separator < fieldEnd) {
          try {
            final int tag = Integer.parseInt(rawMessage.substring(fieldStart, separator));
            fields.put(tag, rawMessage.substring(separator + 1, fieldEnd));
          } catch (NumberFormatException ignored) {
            // A malformed field is irrelevant to a later exact-field match.
          }
        }
        fieldStart = fieldEnd + 1;
      }
      return new WireMessage(fields);
    }

    String field(int tag) {
      return fields.get(tag);
    }

    String requiredField(int tag) {
      final String value = field(tag);
      if (value == null) {
        throw new IllegalStateException("raw FIX message is missing required tag " + tag);
      }
      return value;
    }

    int requiredIntegerField(int tag) {
      return Integer.parseInt(requiredField(tag));
    }
  }

  private final class ObservingLog implements Log {
    private final Log delegateLog;

    private ObservingLog(Log delegateLog) {
      this.delegateLog = delegateLog;
    }

    @Override
    public void clear() {
      incomingMessages.clear();
      delegateLog.clear();
    }

    @Override
    public void onIncoming(String message) {
      delegateLog.onIncoming(message);
      incomingMessages.add(message);
    }

    @Override
    public void onOutgoing(String message) {
      delegateLog.onOutgoing(message);
    }

    @Override
    public void onEvent(String text) {
      delegateLog.onEvent(text);
    }

    @Override
    public void onErrorEvent(String text) {
      delegateLog.onErrorEvent(text);
    }

    @Override
    public void onWarnEvent(String text) {
      delegateLog.onWarnEvent(text);
    }
  }
}
