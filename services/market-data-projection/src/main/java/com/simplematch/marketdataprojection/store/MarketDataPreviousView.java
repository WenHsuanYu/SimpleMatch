package com.simplematch.marketdataprojection.store;

import com.simplematch.marketdataprojection.runtime.LastTrade;
import java.util.Optional;

/** Prior complete snapshot values needed to build the next monotonic instrument view. */
record MarketDataPreviousView(long sequence, Optional<LastTrade> lastTrade) {}
