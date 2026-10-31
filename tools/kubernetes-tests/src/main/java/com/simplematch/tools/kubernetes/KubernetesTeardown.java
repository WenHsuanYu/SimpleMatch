package com.simplematch.tools.kubernetes;

/** Attempts both independently owned boundaries even after API failure or interruption. */
final class KubernetesTeardown {
  private KubernetesTeardown() {}

  static void clean(Step namespace, Step cluster) throws Exception {
    Exception failure = null;
    boolean interrupted = Thread.interrupted();
    try {
      namespace.clean();
    } catch (Exception namespaceFailure) {
      failure = namespaceFailure;
      interrupted |= Thread.interrupted() || namespaceFailure instanceof InterruptedException;
    }
    try {
      cluster.clean();
    } catch (Exception clusterFailure) {
      interrupted |= Thread.interrupted() || clusterFailure instanceof InterruptedException;
      if (failure == null) {
        failure = clusterFailure;
      } else {
        failure.addSuppressed(clusterFailure);
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  @FunctionalInterface
  interface Step {
    /** Cleans this boundary after checking its own resource identities. */
    void clean() throws Exception;
  }
}
