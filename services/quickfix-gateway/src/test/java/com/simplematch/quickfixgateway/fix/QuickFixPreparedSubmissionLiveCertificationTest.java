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
import quickfix.field.ClOrdID;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.HandlInst;
import quickfix.field.MsgSeqNum;
import quickfix.field.MsgType;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TransactTime;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;

/** Prepares one external FIX session and waits for the certification runner to release the order. */
class QuickFixPreparedSubmissionLiveCertificationTest {
  private static final DateTimeFormatter FIX_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")
          .withLocale(Locale.ROOT)
          .withZone(ZoneOffset.UTC);

  @Test
  void preparedSubmission() throws Exception {
    final String host = requiredEnvironment("SIMPLEMATCH_LIVE_FIX_HOST");
    final int port = positivePort("SIMPLEMATCH_LIVE_FIX_PORT");
    final String senderCompId =
        environmentOrDefault("SIMPLEMATCH_LIVE_FIX_SENDER_COMP_ID", "CLIENT");
    final String targetCompId =
        environmentOrDefault("SIMPLEMATCH_LIVE_FIX_TARGET_COMP_ID", "SIMPLEMATCH");
    final Path stateDir = requiredPath("SIMPLEMATCH_RETAINED_FIX_STATE_DIR");
    final Path evidencePath = requiredPath("SIMPLEMATCH_RETAINED_FIX_EVIDENCE");
    final Path readyFile = requiredPath("SIMPLEMATCH_RETAINED_FIX_READY_FILE");
    final Path releaseFile = requiredPath("SIMPLEMATCH_RETAINED_FIX_RELEASE_FILE");
    final Path dictionary = dictionaryPath();
    final String clOrdId = requiredEnvironment("SIMPLEMATCH_LIVE_FIX_CL_ORD_ID");
    final String accountId =
        canonicalAccountId(requiredEnvironment("SIMPLEMATCH_LIVE_FIX_ACCOUNT_ID"));
    final String symbol = requiredEnvironment("SIMPLEMATCH_LIVE_FIX_SYMBOL");
    final String quantity = requiredEnvironment("SIMPLEMATCH_LIVE_FIX_QUANTITY");
    final String price = requiredEnvironment("SIMPLEMATCH_LIVE_FIX_PRICE");
    final int timeoutSeconds =
        integerEnvironment("SIMPLEMATCH_RETAINED_FIX_TIMEOUT_SECONDS", "60", 1, 300);

    Files.createDirectories(stateDir);
    Files.createDirectories(evidencePath.getParent());
    Files.createDirectories(readyFile.getParent());
    Files.deleteIfExists(readyFile);
    Files.deleteIfExists(releaseFile);
    assertThat(dictionary).exists().isRegularFile();

    final SessionSettings settings =
        new SessionSettings(
            writeInitiatorConfig(
                    stateDir, host, port, senderCompId, targetCompId, dictionary)
                .toString());
    final PreparedInitiatorApplication application = new PreparedInitiatorApplication();
    final SocketInitiator initiator =
        new SocketInitiator(
            application,
            new FileStoreFactory(settings),
            settings,
            new FileLogFactory(settings),
            new DefaultMessageFactory());

    try {
      initiator.start();
      assertThat(application.awaitLogon(timeoutSeconds)).as("prepared FIX logon").isTrue();
      final SessionID sessionId = application.sessionId();
      assertThat(sessionId).isNotNull();

      Files.writeString(readyFile, "ready\n");
      awaitRelease(releaseFile, timeoutSeconds);

      final long sentAtEpochMs = Instant.now().toEpochMilli();
      assertThat(
              Session.sendToTarget(
                  newOrder(clOrdId, symbol, quantity, price, accountId), sessionId))
          .isTrue();

      final ExecutionReport report =
          application.awaitExecutionReport(clOrdId, timeoutSeconds);
      writeEvidence(evidencePath, report, sentAtEpochMs);
    } finally {
      initiator.stop(true);
    }
  }

  private void awaitRelease(Path releaseFile, int timeoutSeconds) throws Exception {
    final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (System.nanoTime() < deadlineNanos) {
      if (Files.exists(releaseFile)) {
        return;
      }
      Thread.sleep(20L);
    }
    throw new AssertionError("FIX submission release file was not observed before the deadline");
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

  private void writeEvidence(Path path, ExecutionReport report, long sentAtEpochMs)
      throws Exception {
    final String json =
        "{\n"
            + "  \"phase\":\"SUBMIT\",\n"
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
            + report.getHeader().getInt(MsgSeqNum.FIELD)
            + ",\n"
            + "  \"sentAtEpochMs\":"
            + sentAtEpochMs
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

  private Path requiredPath(String name) {
    return Path.of(requiredEnvironment(name)).toAbsolutePath().normalize();
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

  private String requiredEnvironment(String name) {
    final String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required for prepared FIX certification");
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

  private static final class PreparedInitiatorApplication implements Application {
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

    ExecutionReport awaitExecutionReport(String clOrdId, int timeoutSeconds) throws Exception {
      final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
      while (System.nanoTime() < deadlineNanos) {
        final long remainingNanos = deadlineNanos - System.nanoTime();
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
        if (report.getString(ClOrdID.FIELD).equals(clOrdId)) {
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
