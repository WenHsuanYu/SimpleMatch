package com.simplematch.marketdatapublisher.snapshot;

/**
 * One half-open price interval and the smallest permitted price increment in that interval.
 */
public record TickBand(Long upperExclusiveUnits, long tickSizeUnits) {
    /**
     * Validates the stored fixed-point tick values.
     */
    public TickBand {
        if (upperExclusiveUnits != null && upperExclusiveUnits <= 0) {
            throw new MarketSnapshotValidationException("tick band upper boundary must be positive");
        }
        if (tickSizeUnits <= 0) {
            throw new MarketSnapshotValidationException("tick size must be positive");
        }
    }
}
