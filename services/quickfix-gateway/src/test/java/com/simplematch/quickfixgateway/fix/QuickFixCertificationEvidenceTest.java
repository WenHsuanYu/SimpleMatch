package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.quickfixgateway.config.QuickFixGatewayRuntime;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.RiskSubmissionResult;
import com.simplematch.quickfixgateway.test.FixMessageSnapshot;
import com.simplematch.quickfixgateway.wal.WalAppender;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import quickfix.field.HandlInst;
import quickfix.field.MsgType;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix44.NewOrderSingle;

@ExtendWith(OutputCaptureExtension.class)
class QuickFixCertificationEvidenceTest {
  private static final Instant FIXED_INSTANT = Instant.parse("2024-03-27T08:09:10.123Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

  @TempDir Path tempDir;

  // Verify that the QuickFIX simulator completes the baseline connection, order submission,
  // execution report, and WAL trace flow end to end.
  // Scenario: start the acceptor and initiator, send one new order, and check the execution report,
  // WAL, and log evidence.
  @DisplayName("the QuickFIX simulator verifies the baseline connection and order flow")
  @Test
  void quickFixSimulatorVerifiesSessionLifecycleAndBaselinePath(CapturedOutput output)
      throws Exception {
    final int port = reservePort();
    final Path dictionaryPath = workspaceRoot().resolve("config/quickfix/fix-spec/FIX44.xml");
    assertThat(dictionaryPath).exists();

    final Path acceptorConfigPath = writeAcceptorConfig(port, dictionaryPath);
    final Path initiatorConfigPath = writeInitiatorConfig(port, dictionaryPath);
    final Path walPath = tempDir.resolve("wal").resolve("inbound.wal");

    final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8);
    final OrdersCommandPublisher ordersCommandPublisher = new OrdersCommandPublisher();
    final QuickFixAcceptorLifecycle acceptorLifecycle =
        new QuickFixAcceptorLifecycle(
            new QuickFixApplicationAdapter(
                QuickFixIngressTestFixture.compose(
                    walAppender,
                    ordersCommandPublisher,
                    new AcceptingRiskSubmissionClient(),
                    new QuickFixSessionMessageSender(),
                    new OrderSessionRegistry(),
                    new FixMessageMapper(FIXED_CLOCK),
                    FIXED_CLOCK)),
            new QuickFixGatewayRuntime("test", acceptorConfigPath, walPath));
    final TestInitiatorApplication initiatorApplication = new TestInitiatorApplication();
    final SocketInitiator initiator =
        new SocketInitiator(
            initiatorApplication,
            new FileStoreFactory(new SessionSettings(initiatorConfigPath.toString())),
            new SessionSettings(initiatorConfigPath.toString()),
            new FileLogFactory(new SessionSettings(initiatorConfigPath.toString())),
            new DefaultMessageFactory());

    try {
      acceptorLifecycle.start();
      initiator.start();

      assertThat(initiatorApplication.awaitLogon()).isTrue();

      final SessionID initiatorSessionId = initiatorApplication.sessionId();
      final NewOrderSingle newOrder = newOrder("C1", "AAPL", "10", "101.25", "ACC-1");
      assertThat(Session.sendToTarget(newOrder, initiatorSessionId)).isTrue();

      final Message executionReport = initiatorApplication.awaitApplicationMessage();
      assertThat(
              FixMessageSnapshot.snapshot(
                  executionReport, MsgType.FIELD, 37, 17, 150, 39, 54, 151, 14, 6, 11, 55, 60))
          .startsWith("35=8|37=O-C1|17=E-")
          .contains("|150=A|39=A|54=1|151=10|14=0|6=0|11=C1|55=AAPL|60=2024-03-27T08:09:10.123Z");

      final List<WalRecord> walRecords = walAppender.readAll();
      assertThat(walRecords).hasSize(1);
      final WalRecord walRecord = walRecords.getFirst();
      assertThat(walRecord.orderId()).isEqualTo("O-C1");
      assertThat(walRecord.senderCompId()).isEqualTo("CLIENT");
      assertThat(walRecord.targetCompId()).isEqualTo("SIMPLEMATCH");
      assertThat(walRecord.clOrdId()).isEqualTo("C1");
      assertThat(walRecord.messageType()).isEqualTo(NewOrderSingle.MSGTYPE);
      assertThat(walRecord.rawFix()).contains("35=D").contains("11=C1");
      assertUuidVersionSeven(walRecord.recordId());
      assertThat(executionReport.getString(17)).isEqualTo("E-" + walRecord.recordId());

      final OrderCommand publishedCommand = ordersCommandPublisher.lastPublishedCommand();
      assertThat(publishedCommand.getCommandId()).isEqualTo(walRecord.recordId());
      assertThat(publishedCommand.getMetadata().getEventId()).isEqualTo(walRecord.recordId());
      assertThat(publishedCommand.getSenderCompId()).isEqualTo("CLIENT");
      assertThat(publishedCommand.getTargetCompId()).isEqualTo("SIMPLEMATCH");
      assertThat(publishedCommand.getClOrdId()).isEqualTo("C1");
      assertUuidVersionSeven(publishedCommand.getCommandId());

      initiator.stop();
      assertThat(initiatorApplication.awaitLogout()).isTrue();
      acceptorLifecycle.stop();

      assertLogContains(output, "quickfix-gateway session created: FIX.4.4:SIMPLEMATCH->CLIENT");
      assertLogContains(output, "quickfix-gateway logon: FIX.4.4:SIMPLEMATCH->CLIENT");
      assertLogContains(output, "fromApp session=FIX.4.4:SIMPLEMATCH->CLIENT");
      assertLogContains(output, "quickfix-gateway logout: FIX.4.4:SIMPLEMATCH->CLIENT");
      assertLogContains(output, "quickfix-gateway acceptor started env=test");
      assertLogContains(output, "quickfix-gateway acceptor stopped");
    } finally {
      safeStop(initiator);
      acceptorLifecycle.stop();
      walAppender.close();
    }
  }

  @DisplayName(
      "the QuickFIX simulator returns a rejected risk submission without publishing the order")
  @Test
  void quickFixSimulatorPersistsRejectedRiskSubmissionAndReturnsFixReject() throws Exception {
    final int port = reservePort();
    final Path dictionaryPath = workspaceRoot().resolve("config/quickfix/fix-spec/FIX44.xml");
    final Path acceptorConfigPath = writeAcceptorConfig(port, dictionaryPath);
    final Path initiatorConfigPath = writeInitiatorConfig(port, dictionaryPath);
    final Path walPath = tempDir.resolve("wal").resolve("inbound.wal");
    final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8);
    final OrdersCommandPublisher ordersCommandPublisher = new OrdersCommandPublisher();
    final QuickFixAcceptorLifecycle acceptorLifecycle =
        new QuickFixAcceptorLifecycle(
            new QuickFixApplicationAdapter(
                QuickFixIngressTestFixture.compose(
                    walAppender,
                    ordersCommandPublisher,
                    new RejectingRiskSubmissionClient(),
                    new QuickFixSessionMessageSender(),
                    new OrderSessionRegistry(),
                    new FixMessageMapper(FIXED_CLOCK),
                    FIXED_CLOCK)),
            new QuickFixGatewayRuntime("test", acceptorConfigPath, walPath));
    final TestInitiatorApplication initiatorApplication = new TestInitiatorApplication();
    final SocketInitiator initiator =
        new SocketInitiator(
            initiatorApplication,
            new FileStoreFactory(new SessionSettings(initiatorConfigPath.toString())),
            new SessionSettings(initiatorConfigPath.toString()),
            new FileLogFactory(new SessionSettings(initiatorConfigPath.toString())),
            new DefaultMessageFactory());

    try {
      acceptorLifecycle.start();
      initiator.start();
      assertThat(initiatorApplication.awaitLogon()).isTrue();
      assertThat(
              Session.sendToTarget(
                  newOrder("C1", "AAPL", "10", "101.25", "ACC-1"),
                  initiatorApplication.sessionId()))
          .isTrue();

      final Message executionReport = initiatorApplication.awaitApplicationMessage();
      assertThat(
              FixMessageSnapshot.snapshot(executionReport, MsgType.FIELD, 37, 150, 39, 11, 55, 58))
          .isEqualTo(
              "35=8|37=O-C1|150=8|39=8|11=C1|55=AAPL|58=INSUFFICIENT_BUYING_POWER: available cash is insufficient");
      assertThat(walAppender.readAll())
          .singleElement()
          .satisfies(
              walRecord -> {
                assertThat(walRecord.orderId()).isEqualTo("O-C1");
                assertThat(walRecord.clOrdId()).isEqualTo("C1");
              });
      assertThat(ordersCommandPublisher.lastPublishedCommand()).isNull();
    } finally {
      safeStop(initiator);
      acceptorLifecycle.stop();
      walAppender.close();
    }
  }

  private Path writeAcceptorConfig(int port, Path dictionaryPath) throws IOException {
    final Path configPath = tempDir.resolve("acceptor.cfg");
    Files.writeString(
        configPath,
        "[DEFAULT]\n"
            + "ConnectionType=acceptor\n"
            + "BeginString=FIX.4.4\n"
            + "UseDataDictionary=Y\n"
            + "DataDictionary="
            + dictionaryPath
            + "\n"
            + "HeartBtInt=5\n"
            + "StartTime=00:00:00\n"
            + "EndTime=23:59:59\n"
            + "SocketAcceptPort="
            + port
            + "\n"
            + "SocketReuseAddress=Y\n"
            + "ResetOnLogon=Y\n"
            + "ResetOnLogout=Y\n"
            + "ResetOnDisconnect=Y\n"
            + "FileStorePath="
            + tempDir.resolve("acceptor-store")
            + "\n"
            + "FileLogPath="
            + tempDir.resolve("acceptor-log")
            + "\n"
            + "[SESSION]\n"
            + "SenderCompID=SIMPLEMATCH\n"
            + "TargetCompID=CLIENT\n");
    return configPath;
  }

  private Path writeInitiatorConfig(int port, Path dictionaryPath) throws IOException {
    final Path configPath = tempDir.resolve("initiator.cfg");
    Files.writeString(
        configPath,
        "[DEFAULT]\n"
            + "ConnectionType=initiator\n"
            + "BeginString=FIX.4.4\n"
            + "UseDataDictionary=Y\n"
            + "DataDictionary="
            + dictionaryPath
            + "\n"
            + "HeartBtInt=5\n"
            + "StartTime=00:00:00\n"
            + "EndTime=23:59:59\n"
            + "SocketConnectHost=127.0.0.1\n"
            + "SocketConnectPort="
            + port
            + "\n"
            + "ReconnectInterval=1\n"
            + "ResetOnLogon=Y\n"
            + "ResetOnLogout=Y\n"
            + "ResetOnDisconnect=Y\n"
            + "FileStorePath="
            + tempDir.resolve("initiator-store")
            + "\n"
            + "FileLogPath="
            + tempDir.resolve("initiator-log")
            + "\n"
            + "[SESSION]\n"
            + "SenderCompID=CLIENT\n"
            + "TargetCompID=SIMPLEMATCH\n");
    return configPath;
  }

  private NewOrderSingle newOrder(
      String clientOrderId, String symbol, String quantity, String price, String account) {
    final NewOrderSingle order = new NewOrderSingle();
    order.setString(ClOrdID.FIELD, clientOrderId);
    order.setString(Symbol.FIELD, symbol);
    order.setChar(quickfix.field.Side.FIELD, '1');
    order.setString(OrderQty.FIELD, quantity);
    order.setChar(OrdType.FIELD, '2');
    order.setString(Price.FIELD, price);
    order.setChar(HandlInst.FIELD, '1');
    order.setString(TransactTime.FIELD, "20240327-08:09:10.123");
    order.setString(Account.FIELD, account);
    return order;
  }

  private int reservePort() throws IOException {
    try (java.net.ServerSocket serverSocket = new java.net.ServerSocket(0)) {
      return serverSocket.getLocalPort();
    }
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

  private void safeStop(SocketInitiator initiator) {
    if (initiator == null) {
      return;
    }
    try {
      initiator.stop();
    } catch (Exception stopFailure) {
      System.err.println("best-effort initiator stop failed: " + stopFailure.getMessage());
    }
  }

  private void assertLogContains(CapturedOutput output, String expected)
      throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (output.getOut().contains(expected) || output.getErr().contains(expected)) {
        return;
      }
      Thread.sleep(50);
    }
    assertThat(output.getOut() + output.getErr()).contains(expected);
  }

  private void assertUuidVersionSeven(String rawUuid) {
    assertThat(UUID.fromString(rawUuid).version()).isEqualTo(7);
  }

  private static final class OrdersCommandPublisher
      implements com.simplematch.quickfixgateway.kafka.OrdersCommandPublisher {
    private final AtomicReference<OrderCommand> lastPublishedCommand = new AtomicReference<>();

    @Override
    public CompletableFuture<Void> publish(OrderCommand command) {
      lastPublishedCommand.set(command);
      return CompletableFuture.completedFuture(null);
    }

    private OrderCommand lastPublishedCommand() {
      return lastPublishedCommand.get();
    }
  }

  private static final class AcceptingRiskSubmissionClient implements RiskSubmissionClient {
    @Override
    public RiskSubmissionResult submitNewOrder(OrderCommand command) {
      return new RiskSubmissionResult(command.getOrderId(), true, "", "");
    }

    @Override
    public RiskSubmissionResult submitCancel(OrderCommand command) {
      return new RiskSubmissionResult(command.getOrderId(), true, "", "");
    }
  }

  private static final class RejectingRiskSubmissionClient implements RiskSubmissionClient {
    @Override
    public RiskSubmissionResult submitNewOrder(OrderCommand command) {
      return new RiskSubmissionResult(
          command.getOrderId(),
          false,
          "INSUFFICIENT_BUYING_POWER",
          "available cash is insufficient");
    }

    @Override
    public RiskSubmissionResult submitCancel(OrderCommand command) {
      return new RiskSubmissionResult(command.getOrderId(), true, "", "");
    }
  }

  private static final class TestInitiatorApplication implements Application {
    private final CountDownLatch logonLatch = new CountDownLatch(1);
    private final CountDownLatch logoutLatch = new CountDownLatch(1);
    private final CountDownLatch appMessageLatch = new CountDownLatch(1);
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
      applicationMessage.set(message);
      appMessageLatch.countDown();
    }

    boolean awaitLogon() throws InterruptedException {
      return logonLatch.await(10, TimeUnit.SECONDS);
    }

    boolean awaitLogout() throws InterruptedException {
      return logoutLatch.await(10, TimeUnit.SECONDS);
    }

    Message awaitApplicationMessage() throws InterruptedException {
      assertThat(appMessageLatch.await(10, TimeUnit.SECONDS)).isTrue();
      return applicationMessage.get();
    }

    SessionID sessionId() {
      return sessionId.get();
    }
  }
}
