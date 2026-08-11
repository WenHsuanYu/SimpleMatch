package com.simplematch.marketdataprojection.store;

import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;

/** Stable instrument key derived from one validated final Matching Event. */
record MarketDataInstrument(String venueMic, String symbol) {
  static MarketDataInstrument from(MatchingEvent event) {
    return switch (event.getEventType()) {
      case MATCHING_EVENT_TYPE_ORDER_RESTED -> from(event.getOrderRested().getInstrument());
      case MATCHING_EVENT_TYPE_TRADE_EXECUTED -> from(event.getTradeExecuted().getInstrument());
      case MATCHING_EVENT_TYPE_ORDER_CANCELLED -> from(event.getOrderCancelled().getInstrument());
      case MATCHING_EVENT_TYPE_ORDER_EXPIRED -> from(event.getOrderExpired().getInstrument());
      case MATCHING_EVENT_TYPE_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("matching event type is required");
    };
  }

  private static MarketDataInstrument from(VenueInstrument instrument) {
    return new MarketDataInstrument(instrument.getVenueMic(), instrument.getSymbol());
  }
}
