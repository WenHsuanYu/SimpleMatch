package com.simplematch.marketdatapublisher.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Objects;

/** Flattens normalized semantic instruments into the unchanged canonical JSON shape. */
final class MarketSnapshotCanonicalCodec {
  private final ObjectMapper objectMapper;

  MarketSnapshotCanonicalCodec(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  /** Serializes normalized values without exposing their internal semantic composition. */
  String write(CanonicalSnapshot canonical) {
    try {
      final ObjectNode root = objectMapper.createObjectNode();
      root.put("sourceIdentity", canonical.sourceIdentity());
      root.put("sourceTimestampUnixMs", canonical.sourceTimestampUnixMs());
      root.put("tradingDay", canonical.tradingDay());
      final ArrayNode instruments = root.putArray("instruments");
      canonical.instruments().forEach(instrument -> instruments.add(flatten(instrument)));
      return objectMapper.writeValueAsString(root);
    } catch (IOException exception) {
      throw new MarketSnapshotValidationException(
          "failed to serialize normalized snapshot", exception);
    }
  }

  private ObjectNode flatten(MarketInstrument instrument) {
    final ObjectNode value = objectMapper.createObjectNode();
    value.put("symbol", instrument.symbol());
    value.put("venueMic", instrument.venueMic());
    value.put("boardLotShares", instrument.boardLotShares());
    final ObjectNode tickTable = value.putObject("tickTable");
    final ArrayNode bands = tickTable.putArray("bands");
    instrument.tickTable().bands().forEach(band -> bands.add(flatten(band)));
    value.put("referencePriceUnits", instrument.referencePriceUnits());
    value.put("lowerPriceLimitUnits", instrument.lowerPriceLimitUnits());
    value.put("upperPriceLimitUnits", instrument.upperPriceLimitUnits());
    value.put("eligibilityReason", instrument.eligibilityReason().name());
    return value;
  }

  private ObjectNode flatten(TickBand band) {
    final ObjectNode value = objectMapper.createObjectNode();
    if (band.upperExclusiveUnits() == null) {
      value.putNull("upperExclusiveUnits");
    } else {
      value.put("upperExclusiveUnits", band.upperExclusiveUnits());
    }
    value.put("tickSizeUnits", band.tickSizeUnits());
    return value;
  }
}
