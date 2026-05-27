CREATE SCHEMA IF NOT EXISTS risk_service;

ALTER TABLE risk_service.outbox ADD COLUMN kafka_partition_id INTEGER;