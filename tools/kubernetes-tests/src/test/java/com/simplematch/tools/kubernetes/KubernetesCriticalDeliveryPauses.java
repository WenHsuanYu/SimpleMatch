package com.simplematch.tools.kubernetes;

import java.util.concurrent.Callable;

/** Runs the rejected-FIX check and the database fault under Matching pause ownership. */
final class KubernetesCriticalDeliveryPauses {
  private KubernetesCriticalDeliveryPauses() {}

  @FunctionalInterface
  interface CheckedAction<P> {
    void run(P pause) throws Exception;
  }

  @FunctionalInterface
  interface CheckedResult<P, T> {
    T run(P pause) throws Exception;
  }

  @FunctionalInterface
  interface CheckedStep {
    void run() throws Exception;
  }

  /** Resumes Matching while CDC remains held before starting a fresh bounded outage pause. */
  static <P extends AutoCloseable, T> T run(Callable<P> stop,
      CheckedAction<P> rejection, CheckedStep between, CheckedResult<P, T> outage)
      throws Exception {
    try (var pause = stop.call()) {
      rejection.run(pause);
    }
    between.run();
    try (var pause = stop.call()) {
      return outage.run(pause);
    }
  }
}
