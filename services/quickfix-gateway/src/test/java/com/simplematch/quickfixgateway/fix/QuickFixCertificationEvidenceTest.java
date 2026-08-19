package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.matching.v1.ExecutionEvent;
import com.simplematch.contracts.matching.v1.ExecutionType;
import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.quickfixgateway.config.QuickFixGatewayRuntime;
import com.simplematch.quickfixgateway.kafka.MatchingExecutionConsumer;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.RiskSubmissionResult;
import com.simplematch.quickfixgateway.risk.RiskTestSupport;
import com.simplematch.quickfixgateway.test.FixMessageSnapshot;
import com.simplematch.quickfixgateway.wal.WalAppender;
import com.simplematch.quickfixgateway.wal.WalRecord;
import com.simplematch.quickfixgateway.wal.WalReplayService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
import quickfix.fix44.OrderCancelRequest;

@ExtendWith(OutputCaptureExtension.class)
class QuickFixCertificationEvidenceTest {
  private static final Instant FIXED_INSTANT = Instant.parse("2024-03-27T08:09:10.123Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
  private static final String ACCOUNT_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13";

  @TempDir Path tempDir;

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
    final DriverManagerDataSource quickFixDataSource = quickFixDataSource();
    final QuickFixAcceptorLifecycle acceptorLifecycle =
        new QuickFixAcceptorLifecycle(
            new QuickFixApplicationAdapter(
                QuickFixIngressTestFixture.compose(
                    walAppender,
                    new AcceptingRiskSubmissionClient(),
                    new QuickFixSessionMessageSender(),
                    new OrderSessionRegistry(),
                    new FixMessageMapper(FIXED_CLOCK),
                    FIXED_CLOCK)),
            new QuickFixGatewayRuntime("test", acceptorConfigPath, walPath),
            new QuickFixJdbcAcceptorFactory(() -> quickFixDataSource));
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
      final NewOrderSingle newOrder = newOrder("C1", "AAPL", "10", "101.25", ACCOUNT_ID);
      assertThat(Session.sendToTarget(newOrder, initiatorSessionId)).isTrue();

      final Message executionReport = initiatorApplication.awaitApplicationMessage();
      assertThat(
              FixMessageSnapshot.snapshot(
                  executionReport, MsgType.FIELD, 37, 17, 150, 39, 54, 151, 14, 6, 11, 55, 60))
          .startsWith("35=8|37=O-C1|17=E-")
          .contains(
              "|150=A|39=A|54=1|151=10|14=0|6=0|11=C1|55=AAPL|" + "60=2024-03-27T08:09:10.123Z");

      final List<WalRecord> walRecords = walAppender.readAll();
      assertThat(walRecords).hasSize(1);
      final WalRecord walRecord = walRecords.getFirst();
      assertThat(walRecord.orderId()).isEqualTo("O-C1");
      assertThat(walRecord.senderCompId()).isEqualTo("CLIENT");
      assertThat(walRecord.targetCompId()).isEqualTo("SIMPLEMATCH");
      assertThat(walRecord.clOrdId()).isEqualTo("C1");
      assertThat(walRecord.accountId()).isEqualTo(ACCOUNT_ID);
      assertThat(walRecord.messageType()).isEqualTo(NewOrderSingle.MSGTYPE);
      assertThat(walRecord.rawFix()).contains("35=D").contains("11=C1");
      assertUuidVersionSeven(walRecord.recordId());
      assertThat(executionReport.getString(17)).isEqualTo("E-" + walRecord.recordId());

      initiator.stop();
      assertThat(initiatorApplication.awaitLogout()).isTrue();
      acceptorLifecycle.stop();

      assertLogContains(output, "quickfix-gateway session created: FIX.4.4:SIMPLEMATCH->CLIENT");
      assertLogContains(output, "quickfix-gateway logon: FIX.4.4:SIMPLEMATCH->CLIENT");
      assertLogContains(
          output, "fromApp QuickFIX message accepted for session=FIX.4.4:SIMPLEMATCH->CLIENT");
      assertLogContains(output, "quickfix-gateway logout: FIX.4.4:SIMPLEMATCH->CLIENT");
      assertLogContains(output, "quickfix-gateway acceptor started env=test");
      assertLogContains(output, "quickfix-gateway acceptor stopped");
    } finally {
      safeStop(initiator);
      acceptorLifecycle.stop();
      walAppender.close();
    }
  }

  @DisplayName("the QuickFIX simulator returns a rejected risk submission")
  @Test
  void quickFixSimulatorPersistsRejectedRiskSubmissionAndReturnsFixReject() throws Exception {
    final int port = reservePort();
    final Path dictionaryPath = workspaceRoot().resolve("config/quickfix/fix-spec/FIX44.xml");
    final Path acceptorConfigPath = writeAcceptorConfig(port, dictionaryPath);
    final Path initiatorConfigPath = writeInitiatorConfig(port, dictionaryPath);
    final Path walPath = tempDir.resolve("wal").resolve("inbound.wal");
    final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8);
    final DriverManagerDataSource quickFixDataSource = quickFixDataSource();
    final QuickFixAcceptorLifecycle acceptorLifecycle =
        new QuickFixAcceptorLifecycle(
            new QuickFixApplicationAdapter(
                QuickFixIngressTestFixture.compose(
                    walAppender,
                    new RejectingRiskSubmissionClient(),
                    new QuickFixSessionMessageSender(),
                    new OrderSessionRegistry(),
                    new FixMessageMapper(FIXED_CLOCK),
                    FIXED_CLOCK)),
            new QuickFixGatewayRuntime("test", acceptorConfigPath, walPath),
            new QuickFixJdbcAcceptorFactory(() -> quickFixDataSource));
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
                  newOrder("C1", "AAPL", "10", "101.25", ACCOUNT_ID),
                  initiatorApplication.sessionId()))
          .isTrue();

      final Message executionReport = initiatorApplication.awaitApplicationMessage();
      assertThat(
              FixMessageSnapshot.snapshot(executionReport, MsgType.FIELD, 37, 150, 39, 11, 55, 58))
          .isEqualTo(
              "35=8|37=O-C1|150=8|39=8|11=C1|55=AAPL|"
                  + "58=INSUFFICIENT_BUYING_POWER: available cash is insufficient");
      assertThat(walAppender.readAll())
          .singleElement()
          .satisfies(
              walRecord -> {
                assertThat(walRecord.orderId()).isEqualTo("O-C1");
                assertThat(walRecord.clOrdId()).isEqualTo("C1");
                assertThat(walRecord.accountId()).isEqualTo(ACCOUNT_ID);
              });
    } finally {
      safeStop(initiator);
      acceptorLifecycle.stop();
      walAppender.close();
    }
  }

  @DisplayName("the public gateway certifies duplicate new, cancel, lifecycle, and WAL recovery")
  @Test
  void publicGatewayCertifiesDuplicateCancelLifecycleAndRecovery() throws Exception {
    final Path walPath = tempDir.resolve("certification").resolve("inbound.wal");
    final IdempotentRiskSubmissionClient risk = new IdempotentRiskSubmissionClient();
    final RecordingFixSessionMessageSender sender = new RecordingFixSessionMessageSender();
    final OrderSessionRegistry registry = new OrderSessionRegistry();
    final SessionID sessionId = new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1");

    try (WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      final QuickFixApplicationAdapter adapter =
          new QuickFixApplicationAdapter(
              QuickFixIngressTestFixture.compose(
                  walAppender,
                  risk,
                  sender,
                  registry,
                  new FixMessageMapper(FIXED_CLOCK),
                  FIXED_CLOCK));
      adapter.onLogon(sessionId);
      adapter.fromApp(newOrder("C1", "AAPL", "10", "101.25", ACCOUNT_ID), sessionId);
      adapter.fromApp(newOrder("C1", "AAPL", "10", "101.25", ACCOUNT_ID), sessionId);
      adapter.fromApp(newCancelRequest("C1", "CXL-1", ACCOUNT_ID), sessionId);

      assertThat(risk.newDecisionCount()).isEqualTo(1);
      assertThat(risk.cancelDecisionCount()).isEqualTo(1);
      assertThat(walAppender.readAll()).hasSize(2);
      assertThat(sender.messages()).hasSize(1);

      final MatchingExecutionConsumer executionConsumer =
          new MatchingExecutionConsumer(
              registry, registry, new FixMessageMapper(FIXED_CLOCK), sender);
      executionConsumer.onExecution(cancelledExecution().toByteArray());
      assertThat(sender.messages()).hasSize(2);
      assertThat(
              FixMessageSnapshot.snapshot(
                  sender.messages().getLast(), 35, 37, 17, 150, 39, 54, 151, 14, 6, 11, 41, 55))
          .isEqualTo(
              "35=8|37=O-C1|17=E-CXL-1|150=4|39=4|54=1|151=10|14=0|6=0|"
                  + "11=CXL-1|41=C1|55=AAPL");

      final WalReplayService replayService =
          new WalReplayService(walAppender, RiskTestSupport.submitter(risk));
      assertThat(replayService.replayAll()).isEqualTo(2);
      assertThat(risk.newDecisionCount()).isEqualTo(1);
      assertThat(risk.cancelDecisionCount()).isEqualTo(1);
    }
  }

  private OrderCancelRequest newCancelRequest(
      String originalClientOrderId, String cancelClientOrderId, String account) {
    final OrderCancelRequest cancel = new OrderCancelRequest();
    cancel.setString(quickfix.field.OrigClOrdID.FIELD, originalClientOrderId);
    cancel.setString(ClOrdID.FIELD, cancelClientOrderId);
    cancel.setString(Account.FIELD, account);
    cancel.setString(TransactTime.FIELD, "20240327-08:09:10.123");
    return cancel;
  }

  private ExecutionEvent cancelledExecution() {
    return ExecutionEvent.newBuilder()
        .setMetadata(
            EventMetadata.newBuilder()
                .setSchemaVersion("v1")
                .setEventId("evt-cancelled")
                .setCreatedAtUnixMs(1711526950123L)
                .setSourceService("matching-engine")
                .build())
        .setExecId("E-CXL-1")
        .setOrderId("O-C1")
        .setSymbol("AAPL")
        .setExecutionType(ExecutionType.EXECUTION_TYPE_CANCELED)
        .setClOrdId("C1")
        .setOrigClOrdId("C1")
        .setCancelClOrdId("CXL-1")
        .setSide(Side.SIDE_BUY)
        .build();
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

  private DriverManagerDataSource quickFixDataSource() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl(
        "jdbc:h2:mem:quickfixcert"
            + UUID.randomUUID().toString().replace("-", "")
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS"
            + " quickfix_gateway\\;SET SCHEMA quickfix_gateway");
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/quickfix-gateway")
        .load()
        .migrate();
    return dataSource;
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

  private static final class RecordingFixSessionMessageSender implements FixSessionMessageSender {
    private final List<Message> messages = new ArrayList<>();

    @Override
    public void send(SessionID sessionId, Message message) {
      messages.add(message);
    }

    private List<Message> messages() {
      return messages;
    }
  }

  private static final class IdempotentRiskSubmissionClient implements RiskSubmissionClient {
    private final Map<String, RiskSubmissionResult> outcomes = new java.util.HashMap<>();
    private int newDecisions;
    private int cancelDecisions;

    @Override
    public RiskSubmissionResult submitNewOrder(NewOrderCommand command) {
      final String key =
          "NEW:"
              + command.getSenderCompId()
              + ":"
              + command.getTargetCompId()
              + ":"
              + command.getClOrdId();
      final RiskSubmissionResult existing = outcomes.get(key);
      if (existing != null) {
        return existing;
      }
      newDecisions += 1;
      final RiskSubmissionResult result =
          new RiskSubmissionResult(command.getOrderId(), true, "", "");
      outcomes.put(key, result);
      return result;
    }

    @Override
    public RiskSubmissionResult submitCancel(CancelOrderCommand command) {
      final String key =
          "CANCEL:"
              + command.getSenderCompId()
              + ":"
              + command.getTargetCompId()
              + ":"
              + command.getClOrdId();
      final RiskSubmissionResult existing = outcomes.get(key);
      if (existing != null) {
        return existing;
      }
      cancelDecisions += 1;
      final RiskSubmissionResult result =
          new RiskSubmissionResult(command.getOrderId(), true, "", "");
      outcomes.put(key, result);
      return result;
    }

    private int newDecisionCount() {
      return newDecisions;
    }

    private int cancelDecisionCount() {
      return cancelDecisions;
    }
  }

  private static final class AcceptingRiskSubmissionClient implements RiskSubmissionClient {
    @Override
    public RiskSubmissionResult submitNewOrder(NewOrderCommand command) {
      return new RiskSubmissionResult(command.getOrderId(), true, "", "");
    }

    @Override
    public RiskSubmissionResult submitCancel(CancelOrderCommand command) {
      return new RiskSubmissionResult(command.getOrderId(), true, "", "");
    }
  }

  private static final class RejectingRiskSubmissionClient implements RiskSubmissionClient {
    @Override
    public RiskSubmissionResult submitNewOrder(NewOrderCommand command) {
      return new RiskSubmissionResult(
          command.getOrderId(),
          false,
          "INSUFFICIENT_BUYING_POWER",
          "available cash is insufficient");
    }

    @Override
    public RiskSubmissionResult submitCancel(CancelOrderCommand command) {
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
