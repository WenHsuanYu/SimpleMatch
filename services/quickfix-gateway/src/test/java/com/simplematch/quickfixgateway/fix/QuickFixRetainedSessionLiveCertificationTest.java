package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
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
import quickfix.field.Account;
import quickfix.field.BeginSeqNo;
import quickfix.field.ClOrdID;
import quickfix.field.EndSeqNo;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.HandlInst;
import quickfix.field.MsgSeqNum;
import quickfix.field.MsgType;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.PossDupFlag;
import quickfix.field.Price;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TransactTime;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.ResendRequest;

/** Exercises one retained external FIX session across disconnect, resend, and Gateway restart. */
class QuickFixRetainedSessionLiveCertificationTest {
  private static final DateTimeFormatter FIX_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")
          .withLocale(Locale.ROOT)
          .withZone(ZoneOffset.UTC);

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
    final SocketInitiator initiator =
        new SocketInitiator(
            application,
            new FileStoreFactory(settings),
            settings,
            new FileLogFactory(settings),
            new DefaultMessageFactory());

    try {
      initiator.start();
      assertThat(application.awaitLogon(timeoutSeconds)).as("retained FIX logon").isTrue();
      final SessionID sessionId = application.sessionId();
      assertThat(sessionId).isNotNull();

      switch (phase) {
        case SUBMIT -> submit(application, sessionId, evidencePath, clOrdId, timeoutSeconds);
        case RECEIVE_RESEND ->
            receiveAndResend(application, sessionId, evidencePath, clOrdId, timeoutSeconds);
        case RESEND_ONLY -> resendOnly(application, sessionId, evidencePath, clOrdId, timeoutSeconds);
      }
    } finally {
      initiator.stop(true);
    }
  }

  private void submit(
      RetainedInitiatorApplication application,
      SessionID sessionId,
      Path evidencePath,
      String clOrdId,
      int timeoutSeconds)
      throws Exception {
    final String accountId =
        canonicalAccountId(requiredEnvironment("SIMPLEMATCH_LIVE_FIX_ACCOUNT_ID"));
    final String symbol = requiredEnvironment("SIMPLEMATCH_LIVE_FIX_SYMBOL");
    final String quantity = requiredEnvironment("SIMPLEMATCH_LIVE_FIX_QUANTITY");
    final String price = requiredEnvironment("SIMPLEMATCH_LIVE_FIX_PRICE");

    assertThat(
            Session.sendToTarget(
                newOrder(clOrdId, symbol, quantity, price, accountId), sessionId))
        .isTrue();
    final ExecutionReport report =
        application.awaitExecutionReport(clOrdId, timeoutSeconds, candidate -> true);
    writeEvidence(evidencePath, "SUBMIT", report);

    final String actualExecType = report.getString(ExecType.FIELD);
    final String actualOrdStatus = report.getString(OrdStatus.FIELD);
    final String text = optionalText(report);
    assertThat(actualExecType)
        .as(
            "FIX admission for ClOrdID=%s; OrdStatus=%s; Text=%s",
            clOrdId, actualOrdStatus, text)
        .isEqualTo("A");
    assertThat(actualOrdStatus)
        .as("FIX admission OrdStatus for ClOrdID=%s; Text=%s", clOrdId, text)
        .isEqualTo("A");
  }

  private void receiveAndResend(
      RetainedInitiatorApplication application,
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

    requestResend(sessionId, messageSequence);
    final ExecutionReport duplicate =
        application.awaitExecutionReport(
            clOrdId,
            timeoutSeconds,
            candidate ->
                candidate.getHeader().getInt(MsgSeqNum.FIELD) == messageSequence
                    && candidate.getString(ExecID.FIELD).equals(executionId)
                    && candidate.getHeader().isSetField(PossDupFlag.FIELD)
                    && candidate.getHeader().getBoolean(PossDupFlag.FIELD));

    assertThat(duplicate.getHeader().getInt(MsgSeqNum.FIELD)).isEqualTo(messageSequence);
    assertThat(duplicate.getString(ExecID.FIELD)).isEqualTo(executionId);
    assertThat(duplicate.getHeader().getBoolean(PossDupFlag.FIELD)).isTrue();
    writeEvidence(evidencePath, "RECEIVE_RESEND", duplicate);
  }

  private void resendOnly(
      RetainedInitiatorApplication application,
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

    requestResend(sessionId, expectedSequence);
    final ExecutionReport duplicate =
        application.awaitExecutionReport(
            clOrdId,
            timeoutSeconds,
            candidate ->
                candidate.getHeader().getInt(MsgSeqNum.FIELD) == expectedSequence
                    && candidate.getString(ExecID.FIELD).equals(expectedExecutionId)
                    && candidate.getHeader().isSetField(PossDupFlag.FIELD)
                    && candidate.getHeader().getBoolean(PossDupFlag.FIELD));

    assertThat(duplicate.getString(ExecID.FIELD)).isEqualTo(expectedExecutionId);
    assertThat(duplicate.getHeader().getInt(MsgSeqNum.FIELD)).isEqualTo(expectedSequence);
    assertThat(duplicate.getHeader().getBoolean(PossDupFlag.FIELD)).isTrue();
    writeEvidence(evidencePath, "RESEND_ONLY", duplicate);
  }

  private void requestResend(SessionID sessionId, int messageSequence) throws Exception {
    final ResendRequest request = new ResendRequest();
    request.setInt(BeginSeqNo.FIELD, messageSequence);
    request.setInt(EndSeqNo.FIELD, messageSequence);
    assertThat(Session.sendToTarget(request, sessionId)).isTrue();
  }

  private NewOrderSingle newOrder(
      String clOrdId, String symbol, String quantity, String price, String accountId) {
    final NewOrderSingle order = new NewOrderSingle();
    order.setString(ClOrdID.FIELD, clOrdId);
    order.setString(Symbol.FIELD, symbol);
    order.setChar(quickfix.field.Side.FIELD, '1');
    order.setString(OrderQty.FIELD, quantity);
    order.setChar(OrdType.FIELD, '2');
    order.setString(Price.FIELD, price);
    order.setChar(HandlInst.FIELD, '1');
    order.setString(TransactTime.FIELD, FIX_TIMESTAMP.format(Instant.now()));
    order.setString(Account.FIELD, accountId);
    return order;
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

  private void writeEvidence(Path path, String phase, ExecutionReport report) throws Exception {
    final int messageSequence = report.getHeader().getInt(MsgSeqNum.FIELD);
    final boolean possibleDuplicate =
        report.getHeader().isSetField(PossDupFlag.FIELD)
            && report.getHeader().getBoolean(PossDupFlag.FIELD);
    final String json =
        "{\n"
            + "  \"phase\":\""
            + jsonEscape(phase)
            + "\",\n"
            + "  \"clOrdId\":\""
            + jsonEscape(report.getString(ClOrdID.FIELD))
            + "\",\n"
            + "  \"orderId\":\""
            + jsonEscape(report.getString(OrderID.FIELD))
            + "\",\n"
            + "  \"execId\":\""
            + jsonEscape(report.getString(ExecID.FIELD))
            + "\",\n"
            + "  \"execType\":\""
            + jsonEscape(report.getString(ExecType.FIELD))
            + "\",\n"
            + "  \"ordStatus\":\""
            + jsonEscape(report.getString(OrdStatus.FIELD))
            + "\",\n"
            + "  \"text\":\""
            + jsonEscape(optionalText(report))
            + "\",\n"
            + "  \"msgSeqNum\":"
            + messageSequence
            + ",\n"
            + "  \"possDup\":"
            + possibleDuplicate
            + "\n"
            + "}\n";
    Files.writeString(path, json);
  }

  private String optionalText(ExecutionReport report) throws FieldNotFound {
    return report.isSetField(Text.FIELD) ? report.getString(Text.FIELD) : "";
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

  private String canonicalAccountId(String value) {
    final UUID accountId = UUID.fromString(value);
    assertThat(accountId.toString()).isEqualTo(value.toLowerCase(Locale.ROOT));
    return value;
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
    SUBMIT,
    RECEIVE_RESEND,
    RESEND_ONLY;

    static Phase parse(String value) {
      try {
        return Phase.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
      } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException(
            "SIMPLEMATCH_RETAINED_FIX_PHASE must be submit, receive-resend, or resend-only",
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
