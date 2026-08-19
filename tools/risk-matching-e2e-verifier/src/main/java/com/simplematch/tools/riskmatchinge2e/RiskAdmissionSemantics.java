package com.simplematch.tools.riskmatchinge2e;

import com.simplematch.contracts.risk.v2.AdmissionOutcomeStatus;
import com.simplematch.contracts.risk.v2.GetAdmissionOutcomeResponse;
import com.simplematch.contracts.risk.v2.OrderAdmissionResponse;
import com.simplematch.tools.riskmatchinge2e.RiskMatchingScenario.Scenario;

/** Owns pure identity and terminal-outcome assertions for Risk admission observation. */
final class RiskAdmissionSemantics {
  private RiskAdmissionSemantics() {}

  static void validateSynchronousAccepted(Scenario scenario, OrderAdmissionResponse response) {
    try {
      RiskMatchingScenario.validateAcceptedResponse(scenario, response);
    } catch (IllegalStateException invalid) {
      throw identityFailure(
          VerificationFailure.Stage.ADMISSION_SUBMISSION,
          invalid.getMessage(),
          invalid);
    }
  }

  static ReconciliationState classifyReconciliation(
      Scenario scenario, GetAdmissionOutcomeResponse response) {
    validateCommandId(scenario, response);
    final AdmissionOutcomeStatus status = response.getStatus();
    if (status == AdmissionOutcomeStatus.ADMISSION_OUTCOME_STATUS_PENDING) {
      validateDurableIdentity(scenario, response);
      return ReconciliationState.PENDING;
    }
    if (status == AdmissionOutcomeStatus.ADMISSION_OUTCOME_STATUS_ACCEPTED) {
      validateDurableIdentity(scenario, response);
      return ReconciliationState.ACCEPTED;
    }
    if (status == AdmissionOutcomeStatus.ADMISSION_OUTCOME_STATUS_NOT_FOUND) {
      throw new VerificationFailure(
          VerificationFailure.Stage.ADMISSION_RECONCILIATION,
          VerificationFailure.Code.ADMISSION_NOT_FOUND,
          "Risk returned NOT_FOUND for the durable admission after UNAVAILABLE submission");
    }
    if (status == AdmissionOutcomeStatus.ADMISSION_OUTCOME_STATUS_REJECTED) {
      throw rejected(
          VerificationFailure.Stage.ADMISSION_RECONCILIATION,
          response.getReasonCode(),
          response.getReasonDetail());
    }
    throw unspecifiedOutcome();
  }

  static VerificationFailure rejected(
      VerificationFailure.Stage stage, String reasonCode, String reasonDetail) {
    final String detail =
        reasonDetail == null || reasonDetail.isBlank() ? "no rejection detail" : reasonDetail;
    final String code = reasonCode == null || reasonCode.isBlank() ? "unspecified" : reasonCode;
    return new VerificationFailure(
        stage,
        VerificationFailure.Code.ADMISSION_REJECTED,
        "Risk rejected RM-1 admission: reason_code=" + code + ", reason_detail=" + detail);
  }

  private static void validateCommandId(Scenario scenario, GetAdmissionOutcomeResponse response) {
    requireIdentity(
        VerificationFailure.Stage.ADMISSION_RECONCILIATION,
        scenario.command().commandId().toString(),
        response.getCommandId(),
        "reconciliation command_id");
  }

  private static void validateDurableIdentity(
      Scenario scenario, GetAdmissionOutcomeResponse response) {
    validateCommandId(scenario, response);
    requireIdentity(
        VerificationFailure.Stage.ADMISSION_RECONCILIATION,
        scenario.command().orderId().toString(),
        response.getOrderId(),
        "reconciliation order_id");
    requireIdentity(
        VerificationFailure.Stage.ADMISSION_RECONCILIATION,
        scenario.run().accountId().toString(),
        response.getAccountId(),
        "reconciliation account_id");
  }

  private static VerificationFailure unspecifiedOutcome() {
    return new VerificationFailure(
        VerificationFailure.Stage.ADMISSION_RECONCILIATION,
        VerificationFailure.Code.ADMISSION_RECONCILIATION_FAILED,
        "Risk returned an unspecified admission reconciliation outcome");
  }

  private static void requireIdentity(
      VerificationFailure.Stage stage, String expected, String actual, String field) {
    if (!expected.equals(actual)) {
      throw identityFailure(
          stage,
          field + " mismatch: expected=" + expected + ", actual=" + actual,
          null);
    }
  }

  private static VerificationFailure identityFailure(
      VerificationFailure.Stage stage, String message, Throwable cause) {
    return new VerificationFailure(
        stage,
        VerificationFailure.Code.ADMISSION_IDENTITY_MISMATCH,
        message,
        cause);
  }

  enum ReconciliationState {
    PENDING,
    ACCEPTED
  }
}
