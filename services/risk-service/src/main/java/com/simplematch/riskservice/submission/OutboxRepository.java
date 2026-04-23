package com.simplematch.riskservice.submission;

public interface OutboxRepository {
  void insert(OutboxRecord record);
}