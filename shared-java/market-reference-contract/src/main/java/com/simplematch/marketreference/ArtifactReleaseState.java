package com.simplematch.marketreference;

/** Distinguishes a D-1 review candidate from a deployable final artifact. */
public enum ArtifactReleaseState {
  /** A preliminary universe and route review that cannot be deployed. */
  PRELIMINARY,
  /** A fully priced artifact that may proceed to operator approval and delivery. */
  FINAL
}
