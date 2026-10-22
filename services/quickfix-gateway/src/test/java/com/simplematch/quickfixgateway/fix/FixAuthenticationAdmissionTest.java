package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.RiskSubmissionResult;
import com.simplematch.quickfixgateway.wal.WalAppender;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.RejectLogon;
import quickfix.SessionID;
import quickfix.field.Account;
import quickfix.field.Password;
import quickfix.field.Username;
import quickfix.fix44.Logon;
import quickfix.fix44.NewOrderSingle;

/** Exercises the live FIX callback seam before commands can reach durable admission. */
class FixAuthenticationAdmissionTest {
  private static final SessionID SESSION = new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT");
  private static final String OWN_ACCOUNT = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13";
  private static final String OTHER_ACCOUNT = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c14";
  @TempDir Path directory;

  @Test
  void anonymousLogonCannotDispatchAnApplicationMessage() throws Exception {
    final InboundFixMessageHandler handler = mock(InboundFixMessageHandler.class);
    final QuickFixApplicationAdapter adapter = new QuickFixApplicationAdapter(handler);

    assertThatThrownBy(() -> adapter.fromAdmin(new Logon(), SESSION))
        .isInstanceOf(RejectLogon.class);
    verifyNoInteractions(handler);
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "NULL,NULL",
        "trader,NULL",
        "NULL,secret",
        "wrong,secret",
        "trader,wrong",
        "trader,secret "
      },
      nullValues = "NULL",
      ignoreLeadingAndTrailingWhitespace = false)
  void missingOrInvalidCredentialsNeverReachWalOrRisk(String username, String password)
      throws Exception {
    try (Boundary fixture = boundary()) {
      assertThatThrownBy(() -> fixture.adapter().fromAdmin(logon(username, password), SESSION))
          .isInstanceOf(RejectLogon.class);
      assertThatThrownBy(() -> fixture.adapter().fromApp(order(OWN_ACCOUNT), SESSION))
          .isInstanceOf(IncorrectTagValue.class);
      assertUnwritten(fixture);
    }
  }

  @Test
  void completeSessionIdentityCannotBeReplacedByMatchingCompIds() throws Exception {
    try (Boundary fixture = boundary()) {
      final SessionID alias =
          new SessionID(
              "FIX.4.4", "SIMPLEMATCH", "DESK", "LOCAL", "CLIENT", "REMOTE", "BRANCH", "Q");
      assertThatThrownBy(() -> fixture.adapter().fromAdmin(logon("trader", "secret"), alias))
          .isInstanceOf(RejectLogon.class);
      assertThatThrownBy(() -> fixture.adapter().fromApp(order(OWN_ACCOUNT), alias))
          .isInstanceOf(IncorrectTagValue.class);
      assertUnwritten(fixture);
    }
  }

  @Test
  void processOwnershipAndLogonCallbacksCannotAuthenticateAPeer() throws Exception {
    try (Boundary fixture = boundary()) {
      fixture.adapter().onCreate(SESSION);
      fixture.adapter().onLogon(SESSION);
      assertThatThrownBy(() -> fixture.adapter().fromApp(order(OWN_ACCOUNT), SESSION))
          .isInstanceOf(IncorrectTagValue.class);
      assertUnwritten(fixture);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {OTHER_ACCOUNT, "0194A8F0-7C77-7B38-9E2D-2A5FDD0F7C14"})
  void authenticatedPeerCannotAdmitAnotherAccountsOrder(String accountId) throws Exception {
    try (Boundary fixture = boundary()) {
      authenticate(fixture.adapter());
      assertThatThrownBy(() -> fixture.adapter().fromApp(order(accountId), SESSION))
          .isInstanceOf(IncorrectTagValue.class);
      assertUnwritten(fixture);
    }
  }

  @Test
  void authenticationRequiresSuccessfulProtocolLogonAndIsClearedOnLogoutAndFailedReauthentication()
      throws Exception {
    try (Boundary fixture = boundary()) {
      fixture.adapter().fromAdmin(logon("trader", "secret"), SESSION);
      assertThatThrownBy(() -> fixture.adapter().fromApp(order(OWN_ACCOUNT), SESSION))
          .isInstanceOf(IncorrectTagValue.class);
      fixture.adapter().onLogon(SESSION);
      fixture.adapter().onLogout(SESSION);
      assertThatThrownBy(() -> fixture.adapter().fromApp(order(OWN_ACCOUNT), SESSION))
          .isInstanceOf(IncorrectTagValue.class);
      authenticate(fixture.adapter());
      assertThatThrownBy(() -> fixture.adapter().fromAdmin(logon("trader", "wrong"), SESSION))
          .isInstanceOf(RejectLogon.class);
      fixture.adapter().onLogon(SESSION);
      assertThatThrownBy(() -> fixture.adapter().fromApp(order(OWN_ACCOUNT), SESSION))
          .isInstanceOf(IncorrectTagValue.class);
      assertUnwritten(fixture);
    }
  }

  @Test
  void authorizedAccountsAndCanonicalUuidVariantsStillReachTheOriginalDurableRiskPath()
      throws Exception {
    try (Boundary fixture = boundary(OWN_ACCOUNT, OTHER_ACCOUNT)) {
      authenticate(fixture.adapter());
      fixture.adapter().fromApp(order(OWN_ACCOUNT.toUpperCase(java.util.Locale.ROOT)), SESSION);
      final Message secondOrder = order(OTHER_ACCOUNT);
      secondOrder.setString(11, "AUTH-SECOND");
      fixture.adapter().fromApp(secondOrder, SESSION);

      assertThat(fixture.wal().readAll())
          .extracting(record -> record.accountId())
          .containsExactly(OWN_ACCOUNT, OTHER_ACCOUNT);
      assertThat(fixture.wal().readAll())
          .allSatisfy(
              record -> {
                assertThat(record.schemaVersion()).isEqualTo("v2");
                assertThat(record.session().authenticatedSessionId()).isEqualTo(SESSION.toString());
              });
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not-a-uuid"})
  void malformedAccountsKeepTheExistingExecutionReportRejection(String accountId) throws Exception {
    try (Boundary fixture = boundary()) {
      authenticate(fixture.adapter());
      fixture.adapter().fromApp(order(accountId), SESSION);
      assertUnwritten(fixture);
      final var report = org.mockito.ArgumentCaptor.forClass(Message.class);
      org.mockito.Mockito.verify(fixture.sender())
          .send(org.mockito.ArgumentMatchers.eq(SESSION), report.capture());
      assertThat(report.getValue().getString(58)).contains("INVALID_NEW_ORDER");
    }
  }

  private Boundary boundary(String... accounts) throws Exception {
    final WalAppender wal =
        new WalAppender(directory.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final RiskSubmissionClient risk = mock(RiskSubmissionClient.class);
    when(risk.submitNewOrder(any(NewOrderCommand.class)))
        .thenAnswer(
            invocation -> {
              assertThat(wal.readAll()).isNotEmpty();
              return new RiskSubmissionResult("accepted", true, "", "");
            });
    when(risk.submitCancel(any(com.simplematch.contracts.orders.v2.CancelOrderCommand.class)))
        .thenReturn(new RiskSubmissionResult("accepted", true, "", ""));
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final OrderSessionRegistry registry = new OrderSessionRegistry();
    final InboundFixMessageHandler handler =
        QuickFixIngressTestFixture.compose(
            wal, risk, sender, registry, new FixMessageMapper(), Clock.systemUTC());
    final Set<String> allowed = accounts.length == 0 ? Set.of(OWN_ACCOUNT) : Set.of(accounts);
    final FixCounterparty peer =
        new FixCounterparty(SESSION.toString(), "trader", "secret", allowed);
    return new Boundary(
        wal,
        risk,
        sender,
        registry,
        handler,
        new QuickFixApplicationAdapter(
            handler,
            new FixSessionOwnership(),
            "security-test",
            new FixSessionAccess(List.of(peer), registry)));
  }

  @Test
  void revokedAccountCannotCancelRetainedOrdersByOmittingAccount() throws Exception {
    try (Boundary fixture = boundary(OWN_ACCOUNT, OTHER_ACCOUNT)) {
      authenticate(fixture.adapter());
      fixture.adapter().fromApp(order(OTHER_ACCOUNT), SESSION);
      fixture.adapter().onLogout(SESSION);
      org.mockito.Mockito.clearInvocations(fixture.risk());
      final var revoked =
          new FixCounterparty(SESSION.toString(), "trader", "secret", Set.of(OWN_ACCOUNT));
      final var reconnected =
          new QuickFixApplicationAdapter(
              fixture.handler(),
              new FixSessionOwnership(),
              "after-policy-rotation",
              new FixSessionAccess(List.of(revoked), fixture.registry()));
      authenticate(reconnected);
      final var cancel = new quickfix.fix44.OrderCancelRequest();
      cancel.setString(11, "REVOKED-CANCEL");
      cancel.setString(41, "AUTH-ORDER");

      assertThatThrownBy(() -> reconnected.fromApp(cancel, SESSION))
          .isInstanceOf(IncorrectTagValue.class);
      assertThat(fixture.wal().readAll()).hasSize(1);
      verifyNoInteractions(fixture.risk());
    }
  }

  @Test
  void authorizedSessionCanCancelWithoutRepeatingAccount() throws Exception {
    try (Boundary fixture = boundary()) {
      authenticate(fixture.adapter());
      fixture.adapter().fromApp(order(OWN_ACCOUNT), SESSION);
      final var cancel = new quickfix.fix44.OrderCancelRequest();
      cancel.setString(11, "AUTHORIZED-CANCEL");
      cancel.setString(41, "AUTH-ORDER");

      fixture.adapter().fromApp(cancel, SESSION);

      assertThat(fixture.wal().readAll()).hasSize(2);
      assertThat(fixture.wal().readAll().getLast().accountId()).isEqualTo(OWN_ACCOUNT);
      org.mockito.Mockito.verify(fixture.risk())
          .submitCancel(any(com.simplematch.contracts.orders.v2.CancelOrderCommand.class));
    }
  }

  private static void authenticate(QuickFixApplicationAdapter adapter) throws Exception {
    adapter.fromAdmin(logon("trader", "secret"), SESSION);
    adapter.onLogon(SESSION);
  }

  private static Logon logon(String username, String password) {
    final Logon logon = new Logon();
    if (username != null) {
      logon.setString(Username.FIELD, username);
    }
    if (password != null) {
      logon.setString(Password.FIELD, password);
    }
    return logon;
  }

  private static NewOrderSingle order(String accountId) {
    final NewOrderSingle order = new NewOrderSingle();
    order.setString(11, "AUTH-ORDER");
    order.setString(Account.FIELD, accountId);
    order.setString(55, "2330");
    order.setChar(54, '1');
    order.setString(38, "100");
    order.setString(44, "101.25");
    order.setChar(40, '2');
    return order;
  }

  private static void assertUnwritten(Boundary fixture) throws Exception {
    assertThat(fixture.wal().readAll()).isEmpty();
    verifyNoInteractions(fixture.risk());
  }

  private record Boundary(
      WalAppender wal,
      RiskSubmissionClient risk,
      FixSessionMessageSender sender,
      OrderSessionRegistry registry,
      InboundFixMessageHandler handler,
      QuickFixApplicationAdapter adapter)
      implements AutoCloseable {
    @Override
    public void close() throws java.io.IOException {
      wal.close();
    }
  }
}
