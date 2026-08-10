package com.simplematch.marketreference.builder;

/** The immutable deployment form chosen from the exact final artifact size. */
public enum ArtifactDeliveryType {
  /** Immutable ConfigMap binary data for artifacts up to the conservative size cap. */
  CONFIG_MAP,
  /** Digest-pinned OCI data image and init-container handoff for larger artifacts. */
  OCI_DATA_IMAGE
}
