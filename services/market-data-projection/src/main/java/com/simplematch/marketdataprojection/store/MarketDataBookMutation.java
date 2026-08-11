package com.simplematch.marketdataprojection.store;

import com.simplematch.marketdataprojection.runtime.LastTrade;
import java.util.Optional;

/** Instrument and optional last-trade effect of a successfully applied order-book mutation. */
record MarketDataBookMutation(MarketDataInstrument instrument, Optional<LastTrade> lastTrade) {}
