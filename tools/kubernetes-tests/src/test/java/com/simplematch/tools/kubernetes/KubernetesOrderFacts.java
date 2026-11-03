package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;

/** Collected protocol and durable observations for one public order. */
record KubernetesOrderFacts(JsonNode admission, JsonNode delivery, JsonNode durable,
    JsonNode completed) {}
