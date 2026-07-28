package com.simplematch.config;

import com.github.f4b6a3.uuid.UuidCreator;

import java.util.UUID;

/**
 * Shared UUID helpers used across SimpleMatch services.
 */
public final class SimpleMatchUuids {
    private SimpleMatchUuids() {
    }

    /**
     * Returns a new UUID version 7 value ordered by epoch time.
     *
     * @return a newly generated UUID v7
     */
    public static UUID uuidV7() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}