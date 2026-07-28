package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.matching.v1.ExecutionEvent;
import quickfix.SessionID;

import java.util.Optional;

public interface ExecutionSessionResolver {
    Optional<SessionID> resolveSessionId(ExecutionEvent executionEvent);
}