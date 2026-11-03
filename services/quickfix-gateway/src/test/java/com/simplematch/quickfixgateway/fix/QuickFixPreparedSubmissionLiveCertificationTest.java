package com.simplematch.quickfixgateway.fix;

import org.junit.jupiter.api.Test;

/** Runs the shared prepared client for the external certification entry point. */
class QuickFixPreparedSubmissionLiveCertificationTest extends PreparedFixSubmission {
  @Test
  void preparedSubmission() throws Exception {
    runPreparedSubmission();
  }
}
