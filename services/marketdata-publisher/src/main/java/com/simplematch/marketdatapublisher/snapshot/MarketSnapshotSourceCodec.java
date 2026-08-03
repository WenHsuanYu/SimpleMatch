package com.simplematch.marketdatapublisher.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Decodes the unchanged flat market-source JSON into semantic source values. */
final class MarketSnapshotSourceCodec {
  private static final Set<String> DOCUMENT_FIELDS =
      Set.of("sourceIdentity", "sourceTimestampUnixMs", "tradingDay", "holidays", "instruments");
  private static final Set<String> INSTRUMENT_FIELDS =
      Set.of(
          "symbol",
          "venueMic",
          "securityType",
          "boardLotShares",
          "referencePrice",
          "lowerPriceLimit",
          "upperPriceLimit",
          "tickBands");
  private static final Set<String> TICK_BAND_FIELDS = Set.of("upperExclusive", "tickSize");

  private final ObjectMapper objectMapper;

  MarketSnapshotSourceCodec(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  /** Reads one flat source document and rehydrates its semantic instrument groups. */
  SourceDocument read(byte[] sourceBytes) {
    final JsonNode source = MarketSnapshotJsonReader.parse(objectMapper, sourceBytes);
    MarketSnapshotJsonReader.requireObject(source, "market snapshot source");
    MarketSnapshotJsonReader.rejectUnknownFields(
        source, DOCUMENT_FIELDS, "market snapshot source");
    return new SourceDocument(
        MarketSnapshotJsonReader.textValue(source, "sourceIdentity"),
        MarketSnapshotJsonReader.longValue(source, "sourceTimestampUnixMs"),
        MarketSnapshotJsonReader.textValue(source, "tradingDay"),
        MarketSnapshotJsonReader.textList(source.get("holidays"), "holidays"),
        instrumentList(source.get("instruments")));
  }

  private List<SourceInstrument> instrumentList(JsonNode value) {
    if (value == null || value.isNull()) {
      return List.of();
    }
    return MarketSnapshotJsonReader.nodes(value, "instruments").stream()
        .map(this::rehydrateInstrument)
        .toList();
  }

  private SourceInstrument rehydrateInstrument(JsonNode source) {
    if (source == null || source.isNull()) {
      throw new MarketSnapshotValidationException("instrument is required");
    }
    MarketSnapshotJsonReader.requireObject(source, "instrument");
    MarketSnapshotJsonReader.rejectUnknownFields(source, INSTRUMENT_FIELDS, "instrument");
    return new SourceInstrument(
        new SourceInstrumentIdentity(
            MarketSnapshotJsonReader.textValue(source, "symbol"),
            MarketSnapshotJsonReader.textValue(source, "venueMic")),
        new SourceInstrumentClassification(
            MarketSnapshotJsonReader.textValue(source, "securityType")),
        new SourceTradingTerms(
            MarketSnapshotJsonReader.intValue(source, "boardLotShares"),
            MarketSnapshotJsonReader.textValue(source, "referencePrice"),
            MarketSnapshotJsonReader.textValue(source, "lowerPriceLimit"),
            MarketSnapshotJsonReader.textValue(source, "upperPriceLimit")),
        tickBandList(source.get("tickBands")));
  }

  private List<SourceTickBand> tickBandList(JsonNode value) {
    if (value == null || value.isNull()) {
      return List.of();
    }
    return MarketSnapshotJsonReader.nodes(value, "tickBands").stream()
        .map(
            band -> {
              if (band == null || band.isNull()) {
                throw new MarketSnapshotValidationException("tick band is required");
              }
              MarketSnapshotJsonReader.requireObject(band, "tick band");
              MarketSnapshotJsonReader.rejectUnknownFields(band, TICK_BAND_FIELDS, "tick band");
              return new SourceTickBand(
                  MarketSnapshotJsonReader.textValue(band, "upperExclusive"),
                  MarketSnapshotJsonReader.textValue(band, "tickSize"));
            })
        .toList();
  }
}
