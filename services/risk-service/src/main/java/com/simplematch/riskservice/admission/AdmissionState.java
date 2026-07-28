package com.simplematch.riskservice.admission;

/**
 * Durable local states for one order admission saga.
 */
public enum AdmissionState {
    PENDING,
    ACCEPTED,
    REJECTED
}
