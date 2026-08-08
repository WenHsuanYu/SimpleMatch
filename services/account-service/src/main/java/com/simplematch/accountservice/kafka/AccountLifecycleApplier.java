package com.simplematch.accountservice.kafka;

import com.simplematch.accountservice.reservation.ReservationRecord;
import com.simplematch.contracts.matching.v1.ExecutionEvent;

/** Public account boundary used to apply matching lifecycle events atomically. */
@FunctionalInterface
public interface AccountLifecycleApplier {
  /**
   * Applies one matching execution through Account Authority.
   *
   * @param event matching execution event
   * @return current reservation state after applying or deduplicating the event
   */
  ReservationRecord applyMatchingExecution(ExecutionEvent event);
}
