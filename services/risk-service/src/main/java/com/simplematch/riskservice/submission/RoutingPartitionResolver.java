package com.simplematch.riskservice.submission;

@FunctionalInterface
public interface RoutingPartitionResolver {
  int resolve(String symbol);
}