package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.FileLogFactory;
import quickfix.FileStoreFactory;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.RejectLogon;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.UnsupportedMessageType;
import quickfix.field.BeginSeqNo;
import quickfix.field.ClOrdID;
import quickfix.field.EndSeqNo;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.MsgSeqNum;
import quickfix.field.MsgType;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrigSendingTime;
import quickfix.field.PossDupFlag;
import quickfix.field.Text;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.ResendRequest;

/** Exercises retained FIX delivery and retransmission across Gateway restart. */
class QuickFixRetainedSessionLiveCertificationTest {
  @Test
  void retainedSessionPhase() throws Exception {
    final Phase phase = Phase.parse(requiredEnvironment("SIMPLEMATCH_RETAINED_FIX_PHASE"));
    final String host = requiredEnvironment("SIMPLEMATCH_LIVE_FIX_HOST");
    final int port = positivePort("SIMPLEMATCH_LIVE_FIX_PORT");
    final String senderCompId =
        environmentOrDefault("SIMPLEMATCH_LIVE_FIX_SENDER_COMP_ID", "CLIENT");
    final String targetCompId =
        environmentOrDefault("SIMPLEMATCH_LIVE_FIX_TARGET_COMP_ID", "SIMPLEMATCH");
    final Path stateDir =
        Path.of(requiredEnvironment("SIMPLEMATCH_RETAINED_FIX_STATE_DIR"))
            .toAbsolutePath()
            .normalize();
    final Path evidencePath =
        Path.of(requiredEnvironment("SIMPLEMATCH_RETAINED_FIX_EVIDENCE"))
            .toAbsolutePath()
            .normalize();
    final Path dictionary = dictionaryPath();
    final String clOrdId = requiredEnvironment("SIMPLEMATCH_LIVE_FIX_CL_ORD_ID");
    final int timeoutSeconds =
        integerEnvironment("SIMPLEMATCH_RETAINED_FIX_TIMEOUT_SECONDS", "60", 1, 300);

    Files.createDirectories(stateDir);
    Files.createDirectories(evidencePath.getParent());
    assertThat(dictionary).exists().isRegularFile();

    final SessionSettings settings =
        new SessionSettings(
            writeInitiatorConfig(stateDir, host, port, senderCompId, targetCompId, dictionary)
                .toString());
    final RetainedInitiatorApplication application = new RetainedInitiatorApplication();
    final FixWireLogObserver wireObserver =
        new FixWireLogObserver(new FileLogFactory(settings));
    final SocketInitiator initiator =
        new SocketInitiator(
            application,
            new FileStoreFactory(settings),
            settings,
            wireObserver,
            new DefaultMessageFactory());

    try {
      initiator.start();
      assertThat(application.awaitLogon(timeoutSeconds)).as("retained FIX logon").isTrue();
      final SessionID sessionId = application.sessionId();
      assertThat(sessionId).isNotNull();

      switch (phase) {
        case RECEIVE_RESEND ->
            receiveAndResend(
                application,
                wireObserver,
                sessionId,
                evidencePath,
                clOrdId,
                timeoutSeconds);
        case RESEND_ONLY ->
            resendOnly(wireObserver, sessionId, evidencePath, clOrdId, timeoutSeconds);
      }
    } finally {
      initiator.stop(true);
    }
  }

  private void receiveAndResend(
      RetainedInitiatorApplication application,
      FixWireLogObserver wireObserver,
      SessionID sessionId,
      Path evidencePath,
      String clOrdId,
      int timeoutSeconds)
      throws Exception {
    final ExecutionReport lifecycle =
        application.awaitExecutionReport(
            clOrdId,
            timeoutSeconds,
            candidate -> !"A".equals(candidate.getString(ExecType.FIELD)));
    final int messageSequence = lifecycle.getHeader().getInt(MsgSeqNum.FIELD);
    final String executionId = lifecycle.getString(ExecID.FIELD);
    assertThat(executionId).isNotBlank();

    wireObserver.discardIncoming();
    requestResend(sessionId, messageSequence);
    final FixWireLogObserver.WireMessage duplicate =
        wireObserver.awaitResentExecutionReport(
            clOrdId, messageSequence, executionId, timeoutSeconds);

    assertThat(duplicate.requiredIntegerField(MsgSeqNum.FIELD)).isEqualTo(messageSequence);
    assertThat(duplicate.requiredField(ExecID.FIELD)).isEqualTo(executionId);
    assertThat(duplicate.requiredField(PossDupFlag.FIELD)).isEqualTo("Y");
    assertThat(duplicate.requiredField(OrigSendingTime.FIELD)).isNotBlank();
    writeEvidence(evidencePath, "RECEIVE_RESEND", duplicate);
  }

  private void resendOnly(
      FixWireLogObserver wireObserver,
      SessionID sessionId,
      Path evidencePath,
      String clOrdId,
      int timeoutSeconds)
      throws Exception {
    final int expectedSequence =
        requiredIntegerEnvironment(
            "SIMPLEMATCH_RETAINED_FIX_EXPECTED_MSG_SEQ_NUM", 1, Integer.MAX_VALUE);
    final String expectedExecutionId =
        requiredEnvironment("SIMPLEMATCH_RETAINED_FIX_EXPECTED_EXEC_ID");

    wireObserver.discardIncoming();
    requestResend(sessionId, expectedSequence);
    final FixWireLogObserver.WireMessage duplicate =
        wireObserver.awaitResentExecutionReport(
            clOrdId, expectedSequence, expectedExecutionId, timeoutSeconds);

    assertThat(duplicate.requiredField(ExecID.FIELD)).isEqualTo(expectedExecutionId);
    assertThat(duplicate.requiredIntegerField(MsgSeqNum.FIELD)).isEqualTo(expectedSequence);
    assertThat(duplicate.requiredField(PossDupFlag.FIELD)).isEqualTo("Y");
    assertThat(duplicate.requiredField(OrigSendingTime.FIELD)).isNotBlank();
    writeEvidence(evidencePath, "RESEND_ONLY", duplicate);
  }

  private void requestResend(SessionID sessionId, int messageSequence) throws Exception {
    final ResendRequest request = new ResendRequest();
    request.setInt(BeginSeqNo.FIELD, messageSequence);
    request.setInt(EndSeqNo.FIELD, messageSequence);
    assertThat(Session.sendToTarget(request, sessionId)).isTrue();
  }

  private Path writeInitiatorConfig(
      Path stateDir,
      String host,
      int port,
      String senderCompId,
      String targetCompId,
      Path dictionary)
      throws IOException {
    final Path configPath = stateDir.resolve("retained-initiator.cfg");
    Files.writeString(
        configPath,
        "[DEFAULT]\n"
            + "ConnectionType=initiator\n"
            + "BeginString=FIX.4.4\n"
            + "UseDataDictionary=Y\n"
            + "DataDictionary="
            + dictionary
            + "\n"
            + "HeartBtInt=5\n"
            + "StartTime=00:00:00\n"
            + "EndTime=23:59:59\n"
            + "SocketConnectHost="
            + host
            + "\n"
            + "SocketConnectPort="
            + port
            + "\n"
            + "ReconnectInterval=2\n"
            + "ResetOnLogon=N\n"
            + "ResetOnLogout=N\n"
            + "ResetOnDisconnect=N\n"
            + "FileStorePath="
            + stateDir.resolve("store")
            + "\n"
            + "FileLogPath="
            + stateDir.resolve("log")
            + "\n"
            + "[SESSION]\n"
            + "SenderCompID="
            + senderCompId
            + "\n"
            + "TargetCompID="
            + targetCompId
            + "\n");
    return configPath;
  }

  private void writeEvidence(
      Path path, String phase, FixWireLogObserver.WireMessage message) throws IOException {
    final String text = message.field(Text.FIELD) == null ? "" : message.field(Text.FIELD);
    final String json =
        "{\n"
            + "  \"phase\":\""
            + jsonEscape(phase)
            + "\",\n"
            + "  \"clOrdId\":\""
            + jsonEscape(message.requiredField(ClOrdID.FIELD))
            + "\",\n"
            + "  \"orderId\":\""
            + jsonEscape(message.requiredField(OrderID.FIELD))
            + "\",\n"
            + "  \"execId\":\""
            + jsonEscape(message.requiredField(ExecID.FIELD))
            + "\",\n"
            + "  \"execType\":\""
            + jsonEscape(message.requiredField(ExecType.FIELD))
            + "\",\n"
            + "  \"ordStatus\":\""
            + jsonEscape(message.requiredField(OrdStatus.FIELD))
            + "\",\n"
            + "  \"text\":\""
            + jsonEscape(text)
            + "\",\n"
            + "  \"msgSeqNum\":"
            + message.requiredIntegerField(MsgSeqNum.FIELD)
            + ",\n"
            + "  \"possDup\":true,\n"
            + "  \"origSendingTime\":\""
            + jsonEscape(message.requiredField(OrigSendingTime.FIELD))
            + "\"\n"
            + "}\n";
    Files.writeString(path, json);
  }

  private Path dictionaryPath() {
    final String configured = System.getenv("SIMPLEMATCH_LIVE_FIX_DICTIONARY");
    return Path.of(
            configured == null || configured.isBlank()
                ? workspaceRoot().resolve("config/quickfix/fix-spec/FIX44.xml").toString()
                : configured)
        .toAbsolutePath()
        .normalize();
  }

  private Path workspaceRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.exists(current.resolve("settings.gradle.kts"))
          || Files.exists(current.resolve(".git"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("workspace root not found");
  }

  private int positivePort(String environmentName) {
    final int port = Integer.parseInt(requiredEnvironment(environmentName));
    assertThat(port).isBetween(1, 65535);
    return port;
  }

  private int integerEnvironment(String name, String fallback, int minimum, int maximum) {
    final int value = Integer.parseInt(environmentOrDefault(name, fallback));
    assertThat(value).isBetween(minimum, maximum);
    return value;
  }

  private int requiredIntegerEnvironment(String name, int minimum, int maximum) {
    final int value = Integer.parseInt(requiredEnvironment(name));
    assertThat(value).isBetween(minimum, maximum);
    return value;
  }

  private String requiredEnvironment(String name) {
    final String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required for retained FIX certification");
    }
    return value;
  }

  private String environmentOrDefault(String name, String fallback) {
    final String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }

  private String jsonEscape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private enum Phase {
    RECEIVE_RESEND,
    RESEND_ONLY;

    static Phase parse(String value) {
      try {
        return Phase.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
      } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException(
            "SIMPLEMATCH_RETAINED_FIX_PHASE must be receive-resend or resend-only",
            invalid);
      }
    }
  }

  @FunctionalInterface
  private interface ReportPredicate {
    boolean test(ExecutionReport report) throws Exception;
  }

  private static final class RetainedInitiatorApplication implements Application {
    private final java.util.concurrent.CountDownLatch logonLatch =
        new java.util.concurrent.CountDownLatch(1);
    private final AtomicReference<SessionID> sessionId = new AtomicReference<>();
    private final LinkedBlockingQueue<Message> applicationMessages = new LinkedBlockingQueue<>();

    @Override
    public void onCreate(SessionID createdSessionId) {
      sessionId.compareAndSet(null, createdSessionId);
    }

    @Override
    public void onLogon(SessionID loggedOnSessionId) {
      sessionId.set(loggedOnSessionId);
      logonLatch.countDown();
    }

    @Override
    public void onLogout(SessionID ignored) {}

    @Override
    public void toAdmin(Message message, SessionID ignored) {}

    @Override
    public void toApp(Message message, SessionID ignored) throws DoNotSend {}

    @Override
    public void fromAdmin(Message message, SessionID ignored)
        throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {}

    @Override
    public void fromApp(Message message, SessionID ignored)
        throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
      applicationMessages.add(message);
    }

    boolean awaitLogon(int timeoutSeconds) throws InterruptedException {
      return logonLatch.await(timeoutSeconds, TimeUnit.SECONDS);
    }

    ExecutionReport awaitExecutionReport(
        String clOrdId, int timeoutSeconds, ReportPredicate predicate) throws Exception {
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
      while (System.nanoTime() < deadline) {
        final long remainingNanos = deadline - System.nanoTime();
        final Message message =
            applicationMessages.poll(
                Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)),
                TimeUnit.MILLISECONDS);
        if (message == null) {
          break;
        }
        if (!message.getHeader().getString(MsgType.FIELD).equals(ExecutionReport.MSGTYPE)) {
          continue;
        }
        final ExecutionReport report = (ExecutionReport) message;
        if (!report.getString(ClOrdID.FIELD).equals(clOrdId)) {
          continue;
        }
        if (predicate.test(report)) {
          return report;
        }
      }
      throw new AssertionError("expected FIX ExecutionReport was not observed before the deadline");
    }

    SessionID sessionId() {
      return sessionId.get();
    }
  }
}
