package com.simplematch.riskservice.admission;

import java.util.UUID;

/** Result returned by an admission attempt, including durable saga state. */
public record AdmissionResult(
    UUID commandId,
    UUID orderId,
    UUID accountId,
    AdmissionState state,
    UUID reservationId,
    String reasonCode,
    String reasonDetail,
    String routingSnapshotId,
    Integer routingPartition) {}
