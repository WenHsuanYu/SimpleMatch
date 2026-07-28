package com.simplematch.marketdatapublisher.publication;

/**
 * Test-only outbox decorator that fails after snapshot persistence.
 */
final class TestSnapshotOutbox implements SnapshotOutbox {
    private final SnapshotOutbox delegate;
    private boolean failNextInsert;

    TestSnapshotOutbox(SnapshotOutbox delegate) {
        this.delegate = delegate;
    }

    @Override
    public void insert(SnapshotOutboxRecord record) throws SnapshotPublicationFailure {
        if (failNextInsert) {
            failNextInsert = false;
            throw new IllegalStateException("simulated outbox failure");
        }
        delegate.insert(record);
    }

    void failNextInsertWithUncheckedFailure() {
        failNextInsert = true;
    }

    void clearFailures() {
        failNextInsert = false;
    }
}
