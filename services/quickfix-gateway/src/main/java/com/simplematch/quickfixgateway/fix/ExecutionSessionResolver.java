package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.matching.v1.ExecutionEvent;
import java.util.Optional;
import quickfix.SessionID;

public interface ExecutionSessionResolver {
  Optional<SessionID> resolveSessionId(ExecutionEvent executionEvent);
}
