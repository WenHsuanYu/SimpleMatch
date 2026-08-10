package com.simplematch.marketreference.builder;

import java.net.URI;

/** The authoritative documents required for one offline Taiwan market-reference build. */
public enum OfficialSourceType {
  /** TWSE listed-company registry. */
  TWSE_COMPANIES(
      "twse-listed-companies",
      "https://openapi.twse.com.tw/v1/opendata/t187ap03_L",
      "twse-companies.json"),
  /** TPEx listed-company registry. */
  TPEX_COMPANIES(
      "tpex-listed-companies",
      "https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap03_O",
      "tpex-companies.json"),
  /** TWSE next-trading-day reference and price-limit facts. */
  TWSE_DAILY_LIMITS(
      "twse-daily-price-limits",
      "https://openapi.twse.com.tw/v1/exchangeReport/TWT84U",
      "twse-daily-limits.json"),
  /** TPEx next-trading-day reference and price-limit facts. */
  TPEX_DAILY_LIMITS(
      "tpex-daily-price-limits",
      "https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes",
      "tpex-daily-limits.json"),
  /** TWSE official yearly trading calendar. */
  TWSE_TRADING_CALENDAR(
      "twse-trading-calendar",
      "https://openapi.twse.com.tw/v1/holidaySchedule/holidaySchedule",
      "twse-trading-calendar.json");

  private final String sourceId;
  private final URI endpoint;
  private final String fixtureFileName;

  OfficialSourceType(String sourceId, String endpoint, String fixtureFileName) {
    this.sourceId = sourceId;
    this.endpoint = URI.create(endpoint);
    this.fixtureFileName = fixtureFileName;
  }

  /** Returns the stable artifact provenance identifier. */
  public String sourceId() {
    return sourceId;
  }

  /** Returns the official document endpoint. */
  public URI endpoint() {
    return endpoint;
  }

  /** Returns the deterministic fixture or source-directory filename. */
  public String fixtureFileName() {
    return fixtureFileName;
  }
}
