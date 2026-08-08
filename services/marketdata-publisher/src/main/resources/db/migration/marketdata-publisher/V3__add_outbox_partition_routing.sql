ALTER TABLE marketdata_publisher.outbox
    ADD COLUMN kafka_partition_id INTEGER;

ALTER TABLE marketdata_publisher.outbox
    ADD CONSTRAINT marketdata_outbox_partition_non_negative
        CHECK (kafka_partition_id IS NULL OR kafka_partition_id >= 0);
