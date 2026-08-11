package com.simplematch.queryservice.runtime;

import com.simplematch.queryservice.model.QueryFreshness;

/** Stable v1 response envelope carrying data and source freshness metadata. */
public record QueryReadResponse<T>(T data, QueryFreshness freshness) {}
