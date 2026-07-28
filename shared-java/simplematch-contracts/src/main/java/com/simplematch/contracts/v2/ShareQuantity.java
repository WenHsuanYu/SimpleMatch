package com.simplematch.contracts.v2;

/**
 * A positive whole-share quantity.
 */
public record ShareQuantity(long shares) {
    public ShareQuantity {
        if (shares <= 0) {
            throw new DomainValidationException("share quantity must be positive");
        }
    }

    /**
     * Parses a whole-share quantity without rounding it.
     */
    public static ShareQuantity parse(String value) {
        try {
            return new ShareQuantity(Long.parseLong(value));
        } catch (NumberFormatException exception) {
            throw new DomainValidationException("share quantity must be a signed 64-bit integer");
        }
    }
}
