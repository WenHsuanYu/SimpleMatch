package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.matching.v1.ExecutionEvent;
import java.util.Optional;
import quickfix.SessionID;

/** Resolves the originating FIX session for an asynchronous matching execution. */
public interface ExecutionSessionResolver {
  /** Finds the FIX session associated with an execution event, if it remains locally known. */
  Optional<SessionID> resolveSessionId(ExecutionEvent executionEvent);
}
