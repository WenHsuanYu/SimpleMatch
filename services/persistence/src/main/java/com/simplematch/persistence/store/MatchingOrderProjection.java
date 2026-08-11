package com.simplematch.persistence.store;

import com.simplematch.contracts.common.v2.Side;

/** Immutable order projection values derived from one validated Matching Event. */
record MatchingOrderProjection(
    String orderId,
    String accountId,
    String venueMic,
    String symbol,
    Side side,
    String status,
    long cumulativeQuantityShares,
    long leavesQuantityShares) {}
