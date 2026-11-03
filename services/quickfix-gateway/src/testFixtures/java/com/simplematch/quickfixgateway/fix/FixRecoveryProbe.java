package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.field.Account;
import quickfix.field.BeginSeqNo;
import quickfix.field.ClOrdID;
import quickfix.field.EndSeqNo;
import quickfix.field.ExecID;
import quickfix.field.MsgSeqNum;
import quickfix.field.OrigSendingTime;
import quickfix.field.PossDupFlag;
import quickfix.field.SendingTime;
import quickfix.field.TestReqID;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.ResendRequest;
import quickfix.fix44.TestRequest;

/** Records actual session callbacks, independently of Pod readiness. */
final class FixRecoveryProbe {
  private final Function<String, String> environment;
  private int logonCount;
  private int logoutCount;
  private long logoutAtEpochMs;
  private long reconnectedAtEpochMs;

  FixRecoveryProbe(Function<String, String> environment) {
    this.environment = environment;
  }

  synchronized void onLogon() {
    logonCount++;
    if (logonCount > 1) {
      reconnectedAtEpochMs = System.currentTimeMillis();
    }
  }

  synchronized void onLogout() {
    logoutCount++;
    logoutAtEpochMs = System.currentTimeMillis();
  }

  synchronized ObjectNode connectionEvidence() {
    return new ObjectMapper().createObjectNode()
        .put("logonCount", logonCount)
        .put("logoutCount", logoutCount)
        .put("logoutAtEpochMs", logoutAtEpochMs)
        .put("reconnectedAtEpochMs", reconnectedAtEpochMs);
  }

  /** Keeps the same initiator alive until the runner has replaced and reopened its Gateway. */
  void completeIfRequested(Exchange exchange, FixWireLogObserver observer, int timeoutSeconds)
      throws Exception {
    if (!"true".equals(environment.apply("SIMPLEMATCH_RETAINED_FIX_RECOVERY"))) {
      return;
    }
    final Path release = environmentPath("SIMPLEMATCH_RETAINED_FIX_RECOVERY_RELEASE");
    final long waitingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (!Files.exists(release) && System.nanoTime() < waitingDeadline) {
      Thread.sleep(20L);
    }
    assertThat(release).as("Gateway recovery release").exists();
    final long deadlineEpochMs = Long.parseLong(Files.readString(release).strip());
    final ObjectNode evidence = awaitReconnect(exchange.sessionId(), deadlineEpochMs);

    final ExecutionReport original = exchange.report();
    final String clOrdId = original.getString(ClOrdID.FIELD);
    final int sequence = original.getHeader().getInt(MsgSeqNum.FIELD);
    observer.discardIncoming();
    observer.discardOutgoing();
    final ResendRequest request = new ResendRequest();
    request.setInt(BeginSeqNo.FIELD, sequence);
    request.setInt(EndSeqNo.FIELD, sequence);
    assertThat(Session.sendToTarget(request, exchange.sessionId())).isTrue();
    final var sentRequest = observer.awaitSentResendRequest(sequence, secondsRemaining(deadlineEpochMs));
    final var resent = observer.awaitResentExecutionReport(
        clOrdId, sequence, original.getString(ExecID.FIELD), secondsRemaining(deadlineEpochMs));
    evidence.put("sessionId", exchange.sessionId().toString())
        .put("resendRequestSentAtEpochMs", sentRequest.observedAtEpochMs())
        .put("resentReceivedAtEpochMs", resent.observedAtEpochMs())
        .put("originalSequence", sequence)
        .put("originalExecId", original.getString(ExecID.FIELD))
        .put("originalSendingTime", original.getHeader().getString(SendingTime.FIELD))
        .put("resentSequence", resent.requiredIntegerField(MsgSeqNum.FIELD))
        .put("resentExecId", resent.requiredField(ExecID.FIELD))
        .put("origSendingTime", resent.requiredField(OrigSendingTime.FIELD))
        .put("possDup", "Y".equals(resent.requiredField(PossDupFlag.FIELD)));

    // A true duplicate is intentionally silent: a correlated heartbeat proves
    // processing continued, while the runner checks real durable business effects.
    observer.discardIncoming();
    final NewOrderSingle retry = (NewOrderSingle) exchange.order().clone();
    evidence.put("originalOrderSequence", exchange.order().getHeader().getInt(MsgSeqNum.FIELD))
        .put("originalOrderBodySha256", bodyDigest(exchange.order()))
        .put("retrySentAtEpochMs", System.currentTimeMillis());
    assertThat(Session.sendToTarget(retry, exchange.sessionId())).isTrue();
    evidence.put("retryMessageSequence", retry.getHeader().getInt(MsgSeqNum.FIELD))
        .put("retryOrderBodySha256", bodyDigest(retry))
        .put("clOrdId", retry.getString(ClOrdID.FIELD))
        .put("accountId", retry.getString(Account.FIELD));
    final String testRequestId = "gateway-recovery-" + clOrdId;
    final TestRequest testRequest = new TestRequest(new TestReqID(testRequestId));
    assertThat(Session.sendToTarget(testRequest, exchange.sessionId())).isTrue();
    final var heartbeat = observer.awaitHeartbeat(testRequestId, secondsRemaining(deadlineEpochMs));
    evidence.put("testRequestId", testRequestId)
        .put("heartbeatTestRequestId", heartbeat.requiredField(TestReqID.FIELD));
    final Path path = environmentPath("SIMPLEMATCH_RETAINED_FIX_RECOVERY_EVIDENCE");
    Files.createDirectories(path.getParent());
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), evidence);
  }

  /** Hashes body fields of this simple, group-free NewOrderSingle; headers may advance. */
  static String bodyDigest(Message order) throws Exception {
    final StringBuilder body = new StringBuilder();
    order.iterator().forEachRemaining(field -> body.append(field).append('\u0001'));
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(body.toString().getBytes(StandardCharsets.UTF_8)));
  }

  private static int secondsRemaining(long deadlineEpochMs) {
    final long remaining = deadlineEpochMs - System.currentTimeMillis();
    assertThat(remaining).as("FIX recovery deadline").isPositive();
    return Math.toIntExact((remaining + 999) / 1000);
  }

  private ObjectNode awaitReconnect(SessionID session, long deadlineEpochMs) throws Exception {
    do {
      secondsRemaining(deadlineEpochMs);
      final var evidence = connectionEvidence();
      if (evidence.path("logonCount").asInt() >= 2
          && evidence.path("logoutCount").asInt() > 0
          && Session.lookupSession(session).isLoggedOn()) {
        return evidence;
      }
      Thread.sleep(20);
    } while (true);
  }

  private Path environmentPath(String name) {
    final String value = environment.apply(name);
    assertThat(value).as(name).isNotBlank();
    return Path.of(value).toAbsolutePath().normalize();
  }

  record Exchange(SessionID sessionId, NewOrderSingle order, ExecutionReport report) {}
}
