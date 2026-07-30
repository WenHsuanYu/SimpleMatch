package com.simplematch.riskservice.admission;

import java.util.Objects;

/**
 * FIX identity that participates in the durable admission business key.
 *
 * @param senderCompId the FIX SenderCompID
 * @param targetCompId the FIX TargetCompID
 * @param clOrdId the FIX ClOrdID
 */
public record AdmissionFixIdentity(
        SenderCompId senderCompId,
        TargetCompId targetCompId,
        ClOrdId clOrdId) {
    /** Requires all three typed FIX values. */
    public AdmissionFixIdentity {
        senderCompId = Objects.requireNonNull(senderCompId, "senderCompId");
        targetCompId = Objects.requireNonNull(targetCompId, "targetCompId");
        clOrdId = Objects.requireNonNull(clOrdId, "clOrdId");
    }

    /** Validated FIX SenderCompID. */
    public record SenderCompId(String value) {
        /** Requires a nonblank sender identity. */
        public SenderCompId {
            value = requireNonBlank(value, "sender_comp_id");
        }
    }

    /** Validated FIX TargetCompID. */
    public record TargetCompId(String value) {
        /** Requires a nonblank target identity. */
        public TargetCompId {
            value = requireNonBlank(value, "target_comp_id");
        }
    }

    /** Validated FIX ClOrdID. */
    public record ClOrdId(String value) {
        /** Requires a nonblank client order identity. */
        public ClOrdId {
            value = requireNonBlank(value, "cl_ord_id");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
