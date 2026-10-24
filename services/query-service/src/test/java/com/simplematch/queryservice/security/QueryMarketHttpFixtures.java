package com.simplematch.queryservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketreference.ArtifactChecksum;
import com.simplematch.marketreference.ArtifactIdentity;
import com.simplematch.marketreference.MarketReferenceArtifactCodec;
import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import com.simplematch.queryservice.runtime.QueryProjectionRebuildService;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Objects;

/** Uses the existing verified FINAL artifact; never relabels its historical trading day. */
final class QueryMarketHttpFixtures {
  static final LocalDate DAY = LocalDate.of(2026, 8, 11);

  private QueryMarketHttpFixtures() {}

  static VerifiedMarketReferenceArtifact install(QueryProjectionRebuildService rebuild)
      throws IOException {
    final var verified = verified(bytes());
    rebuild.installMarketReference(verified, 1000);
    return verified;
  }

  static VerifiedMarketReferenceArtifact replacement() throws IOException {
    final var mapper = new ObjectMapper();
    final var tree = mapper.readTree(bytes());
    final var instrument = (com.fasterxml.jackson.databind.node.ObjectNode)
        tree.path("marketSnapshot").path("instruments").get(0);
    instrument.put("referencePriceUnits", 2_000_000);
    instrument.put("lowerPriceLimitUnits", 1_800_000);
    instrument.put("upperPriceLimitUnits", 2_200_000);
    return verified(mapper.writeValueAsBytes(tree));
  }

  private static byte[] bytes() throws IOException {
    try (var resource = Objects.requireNonNull(QueryMarketHttpFixtures.class.getResourceAsStream(
        "/market-reference-fixtures/market_reference.json"))) {
      return resource.readAllBytes();
    }
  }

  private static VerifiedMarketReferenceArtifact verified(byte[] bytes) {
    final String checksum = ArtifactChecksum.sha256(bytes);
    final var artifact = new MarketReferenceArtifactCodec(new ObjectMapper())
        .readVerified(bytes, checksum, DAY);
    return new VerifiedMarketReferenceArtifact(artifact, ArtifactIdentity.of(DAY, checksum));
  }
}
