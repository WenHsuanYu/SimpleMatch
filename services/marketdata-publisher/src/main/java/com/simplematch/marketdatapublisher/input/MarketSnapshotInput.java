package com.simplematch.marketdatapublisher.input;

import com.simplematch.marketdatapublisher.snapshot.PreparedMarketSnapshot;

/**
 * Supplies prepared immutable snapshots without letting trading services reach external exchanges.
 */
@FunctionalInterface
public interface MarketSnapshotInput {
    /**
     * Returns the next normalized immutable snapshot available to the publisher.
     */
    PreparedMarketSnapshot nextSnapshot();
}
