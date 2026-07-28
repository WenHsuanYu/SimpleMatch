package com.simplematch.marketdatapublisher.snapshot;

import java.util.List;
import java.util.Objects;

/**
 * Immutable Taiwan price-tick table used to validate reference and limit prices.
 */
public record TickTable(List<TickBand> bands) {
    /**
     * Creates a validated tick table with exactly one unbounded final band.
     */
    public TickTable {
        bands = List.copyOf(Objects.requireNonNull(bands, "tick bands are required"));
        if (bands.isEmpty()) {
            throw new MarketSnapshotValidationException("tick table must contain at least one band");
        }
        Long previousUpperExclusive = null;
        for (int index = 0; index < bands.size(); index++) {
            final TickBand band = bands.get(index);
            final boolean finalBand = index == bands.size() - 1;
            if (finalBand != (band.upperExclusiveUnits() == null)) {
                throw new MarketSnapshotValidationException("only the final tick band may be unbounded");
            }
            if (previousUpperExclusive != null
                    && band.upperExclusiveUnits() != null
                    && band.upperExclusiveUnits() <= previousUpperExclusive) {
                throw new MarketSnapshotValidationException("tick band boundaries must increase");
            }
            previousUpperExclusive = band.upperExclusiveUnits();
        }
    }

    /**
     * Returns whether a positive fixed-point price lands on the selected tick grid.
     */
    public boolean accepts(long priceUnits) {
        if (priceUnits <= 0) {
            return false;
        }
        for (TickBand band : bands) {
            if (band.upperExclusiveUnits() == null || priceUnits < band.upperExclusiveUnits()) {
                return priceUnits % band.tickSizeUnits() == 0;
            }
        }
        return false;
    }
}
