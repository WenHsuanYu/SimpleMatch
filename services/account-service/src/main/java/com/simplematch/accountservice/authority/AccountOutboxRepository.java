package com.simplematch.accountservice.authority;

/**
 * Persistence port for account lifecycle outbox events.
 */
public interface AccountOutboxRepository {
    /**
     * Inserts one lifecycle event in the caller-owned transaction.
     */
    void insert(AccountLifecycleOutbox event);
}
