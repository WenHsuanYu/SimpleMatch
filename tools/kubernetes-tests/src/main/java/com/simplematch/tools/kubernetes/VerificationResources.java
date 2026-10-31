package com.simplematch.tools.kubernetes;

/** Defines the bounded external-resource operations owned by one verification run. */
public interface VerificationResources {
  /** Checks prerequisites without mutation and returns the observed coordination identity. */
  String preflight() throws Exception;

  /** Creates only resources identified by this invocation, journaling ownership before use. */
  void create(VerificationContext run) throws Exception;

  /** Captures bounded redacted diagnostics while owned resources still exist. */
  void diagnose(VerificationContext run) throws Exception;

  /** Removes only this invocation's resources after checking their exact current ownership. */
  void cleanup(VerificationContext run) throws Exception;
}
