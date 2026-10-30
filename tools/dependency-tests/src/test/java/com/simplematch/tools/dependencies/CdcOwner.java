package com.simplematch.tools.dependencies;

/** Names the two existing transactional-outbox owners and their independent wire contracts. */
enum CdcOwner {
  RISK("risk-service", "Admission", "simplematch.matching.runtime.v1.MatchingCommand"),
  ACCOUNT("account-service", "account_reservation", "simplematch.account.v2.AccountLifecycleEvent");

  final String service;
  final String aggregateType;
  final String payloadType;

  CdcOwner(String service, String aggregateType, String payloadType) {
    this.service = service;
    this.aggregateType = aggregateType;
    this.payloadType = payloadType;
  }

  String schema() {
    return service.replace('-', '_');
  }

  String topic() {
    return this == RISK ? "matching.commands" : "account.lifecycle";
  }

  String connector() {
    return service + "-outbox";
  }
}
