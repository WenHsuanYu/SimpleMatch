package com.simplematch.marketdatapublisher.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.v2.TwdPrice;
import com.simplematch.contracts.v2.VenueMic;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Parses, validates, and normalizes an external daily source before publication begins. */
public final class MarketSnapshotImportService {
  private final MarketSnapshotSourceCodec sourceCodec;
  private final MarketSnapshotCanonicalCodec canonicalCodec;

  /** Creates an importer whose JSON mapper is used only before the transactional seam. */
  public MarketSnapshotImportService(ObjectMapper objectMapper) {
    final ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.sourceCodec = new MarketSnapshotSourceCodec(mapper);
    this.canonicalCodec = new MarketSnapshotCanonicalCodec(mapper);
  }

  /** Converts source bytes to deterministic immutable market-reference content. */
  public PreparedMarketSnapshot prepare(byte[] sourceBytes) {
    if (sourceBytes == null || sourceBytes.length == 0) {
      throw new MarketSnapshotValidationException("market snapshot source is required");
    }
    final SourceDocument source = read(sourceBytes);
    final LocalDate tradingDay = parseDate(source.tradingDay(), "trading day");
    final TaiwanTradingCalendar calendar = new TaiwanTradingCalendar(source.holidays());
    if (!calendar.isTradingDay(tradingDay)) {
      throw new MarketSnapshotValidationException("trading day is not a Taiwan trading day");
    }
    final List<MarketInstrument> instruments =
        source.instruments().stream()
            .map(this::normalizeInstrument)
            .sorted(
                Comparator.comparing(MarketInstrument::symbol)
                    .thenComparing(MarketInstrument::venueMic))
            .toList();
    requireDistinctInstrumentIdentities(instruments);
    final CanonicalSnapshot canonical =
        new CanonicalSnapshot(
            source.sourceIdentity(),
            source.sourceTimestampUnixMs(),
            tradingDay.toString(),
            instruments);
    final String canonicalContent = canonicalCodec.write(canonical);
    return new PreparedMarketSnapshot(
        source.sourceIdentity(),
        source.sourceTimestampUnixMs(),
        tradingDay,
        PreparedMarketSnapshot.checksumFor(canonicalContent),
        instruments,
        canonicalContent);
  }

  private SourceDocument read(byte[] sourceBytes) {
    return sourceCodec.read(sourceBytes);
  }

  private MarketInstrument normalizeInstrument(SourceInstrument source) {
    if (source == null) {
      throw new MarketSnapshotValidationException("instrument is required");
    }
    final TickTable tickTable =
        new TickTable(
            source.tickBands().stream()
                .map(
                    band -> {
                      if (band == null) {
                        throw new MarketSnapshotValidationException("tick band is required");
                      }
                      return new TickBand(
                          band.upperExclusive() == null
                              ? null
                              : parsePrice(band.upperExclusive(), "tick band upper boundary"),
                          parsePrice(band.tickSize(), "tick size"));
                    })
                .toList());
    final SourceInstrumentIdentity identity = source.identity();
    final SourceInstrumentClassification classification = source.classification();
    final SourceTradingTerms tradingTerms = source.tradingTerms();
    final EligibilityReason eligibilityReason =
        eligibility(identity.venueMic(), classification.securityType());
    return new MarketInstrument(
        new InstrumentIdentity(identity.symbol(), identity.venueMic()),
        new InstrumentTradingRules(
            tradingTerms.boardLotShares(),
            tickTable,
            new ReferencePriceBand(
                parsePrice(tradingTerms.referencePrice(), "reference price"),
                parsePrice(tradingTerms.lowerPriceLimit(), "lower price limit"),
                parsePrice(tradingTerms.upperPriceLimit(), "upper price limit"))),
        eligibilityReason);
  }

  private EligibilityReason eligibility(String rawVenue, String securityType) {
    try {
      VenueMic.parse(rawVenue);
    } catch (IllegalArgumentException exception) {
      return EligibilityReason.UNSUPPORTED_VENUE;
    }
    return "COMMON_STOCK".equals(securityType)
        ? EligibilityReason.ELIGIBLE
        : EligibilityReason.UNSUPPORTED_SECURITY_TYPE;
  }

  private long parsePrice(String value, String fieldName) {
    try {
      return TwdPrice.ofDecimal(value).units();
    } catch (IllegalArgumentException exception) {
      throw new MarketSnapshotValidationException(
          fieldName + " must be a positive TWD price", exception);
    }
  }

  private LocalDate parseDate(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new MarketSnapshotValidationException(fieldName + " must be an ISO-8601 date");
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException exception) {
      throw new MarketSnapshotValidationException(
          fieldName + " must be an ISO-8601 date", exception);
    }
  }

  private void requireDistinctInstrumentIdentities(List<MarketInstrument> instruments) {
    final Set<String> identities = new java.util.HashSet<>();
    for (MarketInstrument instrument : instruments) {
      if (!identities.add(instrument.venueMic() + ':' + instrument.symbol())) {
        throw new MarketSnapshotValidationException("duplicate venue instrument identity");
      }
    }
  }

}
