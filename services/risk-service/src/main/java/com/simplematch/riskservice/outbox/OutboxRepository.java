package com.simplematch.riskservice.outbox;

public interface OutboxRepository {
  void insert(OutboxRecord record);
}