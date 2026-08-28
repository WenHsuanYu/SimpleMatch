package com.simplematch.quickfixgateway.risk;

import com.simplematch.contracts.v2.VenueMic;

/** Shared test wiring for the production WAL-to-v2 Risk boundary. */
public final class RiskTestSupport {
  private RiskTestSupport() {}

  /** Wraps one test Risk transport with the same direct v2 mapper used by production. */
  public static RiskCommandSubmitter submitter(RiskSubmissionClient submissionClient) {
    return submitter(submissionClient, new RiskOrderIdentityDeriver());
  }

  /** Wraps one test Risk transport with an explicitly shared order identity authority. */
  public static RiskCommandSubmitter submitter(
      RiskSubmissionClient submissionClient, RiskOrderIdentityDeriver orderIdentityDeriver) {
    return new RiskCommandSubmitter(
        new RiskCommandMapper(VenueMic.parse("XTAI"), orderIdentityDeriver),
        submissionClient);
  }
}
