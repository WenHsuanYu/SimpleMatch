package com.simplematch.marketreference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Reads and writes the strict canonical JSON envelope shared by Risk and Matching. */
public final class MarketReferenceArtifactCodec {
  private final ObjectMapper objectMapper;
  private final MarketReferenceArtifactValidator validator;

  /** Creates a strict codec using the supplied JSON implementation. */
  public MarketReferenceArtifactCodec(ObjectMapper objectMapper) {
    this.objectMapper = canonicalMapper(objectMapper);
    this.validator = new MarketReferenceArtifactValidator();
  }

  /**
   * Serializes a semantically valid artifact as canonical compact UTF-8 bytes.
   *
   * @param artifact artifact to serialize
   * @return deterministic canonical JSON bytes
   */
  public byte[] write(MarketReferenceArtifact artifact) {
    validator.validate(artifact);
    try {
      return objectMapper.writeValueAsBytes(ArtifactJson.from(artifact));
    } catch (JsonProcessingException exception) {
      throw new MarketReferenceValidationException(
          "failed to serialize Market Reference Artifact", exception);
    }
  }

  /**
   * Parses and validates a canonical artifact without trusting an external deployment checksum.
   *
   * @param bytes artifact bytes to parse
   * @return validated artifact model
   */
  public MarketReferenceArtifact read(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw new MarketReferenceValidationException("artifact bytes are required");
    }
    try {
      final MarketReferenceArtifact artifact =
          objectMapper.readValue(bytes, ArtifactJson.class).toDomain();
      validator.validate(artifact);
      return artifact;
    } catch (IOException exception) {
      throw new MarketReferenceValidationException(
          "artifact must be valid canonical JSON", exception);
    }
  }

  /**
   * Verifies the external checksum before parsing and validates a final startup artifact.
   *
   * @param bytes exact mounted artifact bytes
   * @param externalChecksum checksum supplied outside the JSON document
   * @param expectedTradingDay deployment trading day
   * @return validated final artifact
   */
  public MarketReferenceArtifact readVerified(
      byte[] bytes, String externalChecksum, LocalDate expectedTradingDay) {
    ArtifactChecksum.requireCanonical(externalChecksum);
    if (!ArtifactChecksum.sha256(bytes).equals(externalChecksum)) {
      throw new MarketReferenceValidationException(
          "artifact checksum does not match external checksum");
    }
    final MarketReferenceArtifact artifact = read(bytes);
    validator.validateFinal(
        artifact, Objects.requireNonNull(expectedTradingDay, "expected trading day is required"));
    return artifact;
  }

  private static ObjectMapper canonicalMapper(ObjectMapper source) {
    return Objects.requireNonNull(source, "object mapper is required")
        .copy()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private record ArtifactJson(
      MetadataJson metadata,
      MarketRulesJson marketRules,
      MarketSnapshotJson marketSnapshot,
      RoutingPolicyJson routingPolicy) {
    static ArtifactJson from(MarketReferenceArtifact artifact) {
      return new ArtifactJson(
          MetadataJson.from(artifact.metadata()),
          MarketRulesJson.from(artifact.marketRules()),
          MarketSnapshotJson.from(artifact.marketSnapshot()),
          RoutingPolicyJson.from(artifact.routingPolicy()));
    }

    MarketReferenceArtifact toDomain() {
      return new MarketReferenceArtifact(
          metadata.toDomain(),
          marketRules.toDomain(),
          marketSnapshot.toDomain(),
          routingPolicy.toDomain());
    }
  }

  private record MetadataJson(
      int schemaVersion,
      ArtifactReleaseState releaseState,
      String tradingDay,
      String routingAlgorithmVersion,
      List<SourceJson> sourceProvenance) {
    static MetadataJson from(ArtifactMetadata metadata) {
      return new MetadataJson(
          metadata.schemaVersion(),
          metadata.releaseState(),
          metadata.tradingDay().toString(),
          metadata.routingAlgorithmVersion(),
          metadata.sourceProvenance().stream().map(SourceJson::from).toList());
    }

    ArtifactMetadata toDomain() {
      return new ArtifactMetadata(
          schemaVersion,
          releaseState,
          parseDate(tradingDay, "artifact trading day"),
          routingAlgorithmVersion,
          sourceProvenance.stream().map(SourceJson::toDomain).toList());
    }
  }

  private record SourceJson(
      String sourceId,
      String sourceUrl,
      String sourceDate,
      long retrievedAtUnixMs,
      String contentSha256) {
    static SourceJson from(SourceProvenance source) {
      return new SourceJson(
          source.sourceId(),
          source.sourceUrl(),
          source.sourceDate().toString(),
          source.retrievedAtUnixMs(),
          source.contentSha256());
    }

    SourceProvenance toDomain() {
      return new SourceProvenance(
          sourceId,
          sourceUrl,
          parseDate(sourceDate, "source date"),
          retrievedAtUnixMs,
          contentSha256);
    }
  }

  private record MarketRulesJson(
      String ruleSetVersion,
      String currency,
      List<MarketRuleJson> rules,
      List<TickTableJson> tickTables) {
    static MarketRulesJson from(MarketRules marketRules) {
      return new MarketRulesJson(
          marketRules.ruleSetVersion(),
          marketRules.currency(),
          marketRules.rules().stream().map(MarketRuleJson::from).toList(),
          marketRules.tickTables().stream().map(TickTableJson::from).toList());
    }

    MarketRules toDomain() {
      return new MarketRules(
          ruleSetVersion,
          currency,
          rules.stream().map(MarketRuleJson::toDomain).toList(),
          tickTables.stream().map(TickTableJson::toDomain).toList());
    }
  }

  private record MarketRuleJson(String ruleId, int boardLotShares, String tickTableId) {
    static MarketRuleJson from(MarketRule rule) {
      return new MarketRuleJson(rule.ruleId(), rule.boardLotShares(), rule.tickTableId());
    }

    MarketRule toDomain() {
      return new MarketRule(ruleId, boardLotShares, tickTableId);
    }
  }

  private record TickTableJson(String tickTableId, List<TickBandJson> bands) {
    static TickTableJson from(TickTableDefinition table) {
      return new TickTableJson(
          table.tickTableId(), table.bands().stream().map(TickBandJson::from).toList());
    }

    TickTableDefinition toDomain() {
      return new TickTableDefinition(
          tickTableId, bands.stream().map(TickBandJson::toDomain).toList());
    }
  }

  private record TickBandJson(Long upperExclusiveUnits, long tickSizeUnits) {
    static TickBandJson from(TickBandDefinition band) {
      return new TickBandJson(band.upperExclusiveUnits(), band.tickSizeUnits());
    }

    TickBandDefinition toDomain() {
      return new TickBandDefinition(upperExclusiveUnits, tickSizeUnits);
    }
  }

  private record MarketSnapshotJson(List<InstrumentJson> instruments) {
    static MarketSnapshotJson from(MarketSnapshot snapshot) {
      return new MarketSnapshotJson(
          snapshot.instruments().stream().map(InstrumentJson::from).toList());
    }

    MarketSnapshot toDomain() {
      return new MarketSnapshot(instruments.stream().map(InstrumentJson::toDomain).toList());
    }
  }

  private record InstrumentJson(
      String venueMic,
      String symbol,
      InstrumentEligibility eligibility,
      String ineligibilityReason,
      String marketRuleId,
      Long referencePriceUnits,
      Long lowerPriceLimitUnits,
      Long upperPriceLimitUnits) {
    static InstrumentJson from(ArtifactInstrument instrument) {
      return new InstrumentJson(
          instrument.instrument().venueMic(),
          instrument.instrument().symbol(),
          instrument.eligibility(),
          instrument.ineligibilityReason(),
          instrument.marketRuleId(),
          instrument.referencePriceUnits(),
          instrument.lowerPriceLimitUnits(),
          instrument.upperPriceLimitUnits());
    }

    ArtifactInstrument toDomain() {
      return new ArtifactInstrument(
          new InstrumentRef(venueMic, symbol),
          eligibility,
          ineligibilityReason,
          marketRuleId,
          referencePriceUnits,
          lowerPriceLimitUnits,
          upperPriceLimitUnits);
    }
  }

  private record RoutingPolicyJson(
      String algorithmVersion,
      int partitionCount,
      int maximumInstrumentsPerPartition,
      List<RoutingAssignmentJson> assignments) {
    static RoutingPolicyJson from(RoutingPolicy routingPolicy) {
      return new RoutingPolicyJson(
          routingPolicy.algorithmVersion(),
          routingPolicy.partitionCount(),
          routingPolicy.maximumInstrumentsPerPartition(),
          routingPolicy.assignments().stream().map(RoutingAssignmentJson::from).toList());
    }

    RoutingPolicy toDomain() {
      return new RoutingPolicy(
          algorithmVersion,
          partitionCount,
          maximumInstrumentsPerPartition,
          assignments.stream().map(RoutingAssignmentJson::toDomain).toList());
    }
  }

  private record RoutingAssignmentJson(String venueMic, String symbol, int partitionId) {
    static RoutingAssignmentJson from(RoutingAssignment assignment) {
      return new RoutingAssignmentJson(
          assignment.instrument().venueMic(),
          assignment.instrument().symbol(),
          assignment.partitionId());
    }

    RoutingAssignment toDomain() {
      return new RoutingAssignment(new InstrumentRef(venueMic, symbol), partitionId);
    }
  }

  private static LocalDate parseDate(String value, String field) {
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException exception) {
      throw new MarketReferenceValidationException(field + " must be an ISO date", exception);
    }
  }
}
