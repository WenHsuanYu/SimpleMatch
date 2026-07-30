package com.simplematch.marketdatapublisher.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.v2.TwdPrice;
import com.simplematch.contracts.v2.VenueMic;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Parses, validates, and normalizes an external daily source before publication begins. */
public final class MarketSnapshotImportService {
  private final ObjectMapper objectMapper;

  /** Creates an importer whose JSON mapper is used only before the transactional seam. */
  public MarketSnapshotImportService(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  /** Converts source bytes to deterministic immutable market-reference content. */
  public PreparedMarketSnapshot prepare(byte[] sourceBytes) {
    if (sourceBytes == null || sourceBytes.length == 0) {
      throw new MarketSnapshotValidationException("market snapshot source is required");
    }
    final SourceDocument source = read(sourceBytes);
    final LocalDate tradingDay = parseDate(source.tradingDay(), "trading day");
    final TaiwanTradingCalendar calendar = new TaiwanTradingCalendar(safeList(source.holidays()));
    if (!calendar.isTradingDay(tradingDay)) {
      throw new MarketSnapshotValidationException("trading day is not a Taiwan trading day");
    }
    final List<MarketInstrument> instruments =
        safeList(source.instruments()).stream()
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
    final String canonicalContent = write(canonical);
    return new PreparedMarketSnapshot(
        source.sourceIdentity(),
        source.sourceTimestampUnixMs(),
        tradingDay,
        PreparedMarketSnapshot.checksumFor(canonicalContent),
        instruments,
        canonicalContent);
  }

  private SourceDocument read(byte[] sourceBytes) {
    try {
      final SourceDocument source = objectMapper.readValue(sourceBytes, SourceDocument.class);
      if (source == null) {
        throw new MarketSnapshotValidationException("market snapshot source is required");
      }
      return source;
    } catch (IOException exception) {
      throw new MarketSnapshotValidationException(
          "market snapshot source must be valid JSON", exception);
    }
  }

  private MarketInstrument normalizeInstrument(SourceInstrument source) {
    if (source == null) {
      throw new MarketSnapshotValidationException("instrument is required");
    }
    final TickTable tickTable =
        new TickTable(
            safeList(source.tickBands()).stream()
                .map(
                    band ->
                        new TickBand(
                            band.upperExclusive() == null
                                ? null
                                : parsePrice(band.upperExclusive(), "tick band upper boundary"),
                            parsePrice(band.tickSize(), "tick size")))
                .toList());
    final EligibilityReason eligibilityReason =
        eligibility(source.venueMic(), source.securityType());
    return new MarketInstrument(
        source.symbol(),
        normalizedVenue(source.venueMic()),
        source.boardLotShares(),
        tickTable,
        parsePrice(source.referencePrice(), "reference price"),
        parsePrice(source.lowerPriceLimit(), "lower price limit"),
        parsePrice(source.upperPriceLimit(), "upper price limit"),
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

  private String normalizedVenue(String rawVenue) {
    return rawVenue == null ? "UNKNOWN" : rawVenue.trim().toUpperCase(java.util.Locale.ROOT);
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

  private String write(CanonicalSnapshot canonical) {
    try {
      return objectMapper.writeValueAsString(canonical);
    } catch (IOException exception) {
      throw new MarketSnapshotValidationException(
          "failed to serialize normalized snapshot", exception);
    }
  }

  private <T> List<T> safeList(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  private void requireDistinctInstrumentIdentities(List<MarketInstrument> instruments) {
    final Set<String> identities = new java.util.HashSet<>();
    for (MarketInstrument instrument : instruments) {
      if (!identities.add(instrument.venueMic() + ':' + instrument.symbol())) {
        throw new MarketSnapshotValidationException("duplicate venue instrument identity");
      }
    }
  }

  private record SourceDocument(
      String sourceIdentity,
      long sourceTimestampUnixMs,
      String tradingDay,
      List<String> holidays,
      List<SourceInstrument> instruments) {}

  private record SourceInstrument(
      String symbol,
      String venueMic,
      String securityType,
      int boardLotShares,
      String referencePrice,
      String lowerPriceLimit,
      String upperPriceLimit,
      List<SourceTickBand> tickBands) {}

  private record SourceTickBand(String upperExclusive, String tickSize) {}

  private record CanonicalSnapshot(
      String sourceIdentity,
      long sourceTimestampUnixMs,
      String tradingDay,
      List<MarketInstrument> instruments) {}

  private static final class TaiwanTradingCalendar {
    private final List<LocalDate> holidays;

    private TaiwanTradingCalendar(List<String> rawHolidays) {
      this.holidays = rawHolidays.stream().map(value -> parseHoliday(value)).toList();
    }

    private static LocalDate parseHoliday(String value) {
      if (value == null || value.isBlank()) {
        throw new MarketSnapshotValidationException("holiday must be an ISO-8601 date");
      }
      try {
        return LocalDate.parse(value);
      } catch (DateTimeParseException exception) {
        throw new MarketSnapshotValidationException("holiday must be an ISO-8601 date", exception);
      }
    }

    private boolean isTradingDay(LocalDate day) {
      return day.getDayOfWeek() != DayOfWeek.SATURDAY
          && day.getDayOfWeek() != DayOfWeek.SUNDAY
          && !holidays.contains(day);
    }
  }
}
