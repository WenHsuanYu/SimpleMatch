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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import quickfix.field.MsgType;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;

/** Runs an opt-in FIX 4.4 order-admission check against an externally deployed Gateway. */
class QuickFixLiveCertificationTest {
  private static final DateTimeFormatter FIX_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")
          .withLocale(Locale.ROOT)
          .withZone(ZoneOffset.UTC);

  @TempDir Path tempDir;

  @Test
  void externalGatewayLogsOnAndReturnsAnAdmissionExecutionReport() throws Exception {
    final String host = requiredEnvironment("SIMPLEMATCH_LIVE_FIX_HOST");
    final int port = positivePort("SIMPLEMATCH_LIVE_FIX_PORT");
    final String senderCompId = environmentOrDefault("SIMPLEMATCH_LIVE_FIX_SENDER_COMP_ID", "CLIENT");
    final String targetCompId =
        environmentOrDefault("SIMPLEMATCH_LIVE_FIX_TARGET_COMP_ID", "SIMPLEMATCH");
    final String accountId = canonicalAccountId(requiredEnvironment("SIMPLEMATCH_LIVE_FIX_ACCOUNT_ID"));
    final String symbol = requiredEnvironment("SIMPLEMATCH_LIVE_FIX_SYMBOL");
    final String quantity = environmentOrDefault("SIMPLEMATCH_LIVE_FIX_QUANTITY", "10");
    final String price = environmentOrDefault("SIMPLEMATCH_LIVE_FIX_PRICE", "101.25");
    final String clOrdId =
        environmentOrDefault(
            "SIMPLEMATCH_LIVE_FIX_CL_ORD_ID", "LIVE-" + Instant.now().getEpochSecond());
    final boolean expectAccepted =
        booleanEnvironment("SIMPLEMATCH_LIVE_FIX_EXPECT_ACCEPTED", true);
    final Path dictionary = dictionaryPath();
    assertThat(dictionary).exists().isRegularFile();

    final Path initiatorConfig = writeInitiatorConfig(
        host, port, senderCompId, targetCompId, dictionary);
    final LiveInitiatorApplication application = new LiveInitiatorApplication();
    final SessionSettings settings = new SessionSettings(initiatorConfig.toString());
    final SocketInitiator initiator =
        new SocketInitiator(
            application,
            new FileStoreFactory(settings),
            settings,
            new FileLogFactory(settings),
            new DefaultMessageFactory());

    try {
      initiator.start();
      assertThat(application.awaitLogon()).as("FIX logon").isTrue();
      assertThat(application.sessionId()).isNotNull();

      final NewOrderSingle order = newOrder(clOrdId, symbol, quantity, price, accountId);
      assertThat(Session.sendToTarget(order, application.sessionId())).isTrue();

      final Message message = application.awaitApplicationMessage();
      assertThat(message.getHeader().getString(MsgType.FIELD)).isEqualTo("8");
      assertThat(message).isInstanceOf(ExecutionReport.class);
      final ExecutionReport report = (ExecutionReport) message;
      assertThat(report.getString(ClOrdID.FIELD)).isEqualTo(clOrdId);
      assertThat(report.getString(Symbol.FIELD)).isEqualTo(symbol);
      assertThat(report.getString(ExecID.FIELD)).isNotBlank();
      assertThat(report.getString(ExecType.FIELD)).isNotBlank();
      assertThat(report.getString(OrdStatus.FIELD)).isNotBlank();
      if (expectAccepted) {
        assertThat(report.getString(OrdStatus.FIELD))
            .as("the live test order must be admitted, not rejected")
            .isIn("A", "0", "1", "2");
      }
    } finally {
      initiator.stop();
      application.awaitLogout();
    }
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
      String host, int port, String senderCompId, String targetCompId, Path dictionary)
      throws IOException {
    final Path configPath = tempDir.resolve("live-initiator.cfg");
    Files.writeString(
        configPath,
        "[DEFAULT]\n"
            + "ConnectionType=initiator\n"
            + "BeginString=FIX.4.4\n"
            + "UseDataDictionary=Y\n"
            + "DataDictionary="
            + dictionary
            + "\n"
            + "HeartBtInt=30\n"
            + "StartTime=00:00:00\n"
            + "EndTime=23:59:59\n"
            + "SocketConnectHost="
            + host
            + "\n"
            + "SocketConnectPort="
            + port
            + "\n"
            + "ReconnectInterval=5\n"
            + "ResetOnLogon=N\n"
            + "ResetOnLogout=N\n"
            + "ResetOnDisconnect=N\n"
            + "FileStorePath="
            + tempDir.resolve("store")
            + "\n"
            + "FileLogPath="
            + tempDir.resolve("log")
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
      if (Files.exists(current.resolve("settings.gradle.kts")) || Files.exists(current.resolve(".git"))) {
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

  private String requiredEnvironment(String name) {
    final String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required for live QuickFIX certification");
    }
    return value;
  }

  private String environmentOrDefault(String name, String fallback) {
    final String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }

  private boolean booleanEnvironment(String name, boolean fallback) {
    final String value = environmentOrDefault(name, Boolean.toString(fallback));
    if ("true".equalsIgnoreCase(value)) {
      return true;
    }
    if ("false".equalsIgnoreCase(value)) {
      return false;
    }
    throw new IllegalArgumentException(name + " must be true or false");
  }

  private static final class LiveInitiatorApplication implements Application {
    private final CountDownLatch logonLatch = new CountDownLatch(1);
    private final CountDownLatch logoutLatch = new CountDownLatch(1);
    private final CountDownLatch applicationMessageLatch = new CountDownLatch(1);
    private final AtomicReference<SessionID> sessionId = new AtomicReference<>();
    private final AtomicReference<Message> applicationMessage = new AtomicReference<>();

    @Override
    public void onCreate(SessionID sessionId) {
      this.sessionId.compareAndSet(null, sessionId);
    }

    @Override
    public void onLogon(SessionID sessionId) {
      this.sessionId.set(sessionId);
      logonLatch.countDown();
    }

    @Override
    public void onLogout(SessionID sessionId) {
      logoutLatch.countDown();
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {}

    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {}

    @Override
    public void fromAdmin(Message message, SessionID sessionId)
        throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {}

    @Override
    public void fromApp(Message message, SessionID sessionId)
        throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
      applicationMessage.compareAndSet(null, message);
      applicationMessageLatch.countDown();
    }

    boolean awaitLogon() throws InterruptedException {
      return logonLatch.await(30, TimeUnit.SECONDS);
    }

    boolean awaitLogout() throws InterruptedException {
      return logoutLatch.await(10, TimeUnit.SECONDS);
    }

    Message awaitApplicationMessage() throws InterruptedException {
      assertThat(applicationMessageLatch.await(30, TimeUnit.SECONDS))
          .as("live FIX application response")
          .isTrue();
      return applicationMessage.get();
    }

    SessionID sessionId() {
      return sessionId.get();
    }
  }
}
