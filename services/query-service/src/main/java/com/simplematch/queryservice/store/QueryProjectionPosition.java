package com.simplematch.queryservice.store;

/** Exact source position carried through one durable query projection operation. */
record QueryProjectionPosition(int partition, long offset, long observedAtUnixMs) {}
