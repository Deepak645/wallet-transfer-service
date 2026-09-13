package com.paytm.wallet.repository;

import com.paytm.wallet.domain.Wallet;

import java.util.Optional;

public interface WalletRepository {

    Optional<Wallet> findById(long id);

    Optional<Wallet> findByUserId(String userId);

    /**
     * Race-free get-or-create: INSERT ... ON CONFLICT (user_id) DO NOTHING.
     * Returns the inserted row if this call won the race, or empty if a
     * wallet for this user already existed (caller should then look it up
     * with {@link #findByUserId(String)}).
     */
    Optional<Wallet> insertIfAbsent(String userId);

    /**
     * Locks the row with SELECT ... FOR UPDATE. Caller is responsible for
     * lock ordering (ascending wallet id) to avoid deadlocks — this method
     * only locks the single row requested.
     */
    Optional<Wallet> lockById(long id);

    /**
     * Overwrites the balance for a row the caller already holds a
     * FOR UPDATE lock on (via {@link #lockById(long)}) in the same
     * transaction.
     */
    void updateBalance(long id, long newBalancePaise);
}
