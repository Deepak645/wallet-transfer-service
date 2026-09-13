package com.paytm.wallet.repository;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.TransferStatus;

import java.util.Optional;

public interface TransferRepository {

    Optional<Transfer> findById(long id);

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    /**
     * Race-free idempotency-key claim: INSERT ... ON CONFLICT (idempotency_key)
     * DO NOTHING, with status PENDING. Returns the inserted row if this call
     * won the race, or empty if a transfer with this key already exists
     * (caller should then look it up with {@link #findByIdempotencyKey(String)}
     * to compare bodies / return the original result).
     */
    Optional<Transfer> insertPending(long fromWalletId, long toWalletId, long amountPaise, String idempotencyKey);

    /**
     * Finalizes the outcome of a transfer this call already owns (i.e. the
     * row it got back from {@link #insertPending}). Must be called in the
     * same transaction as the wallet balance changes it reflects.
     */
    void updateStatus(long id, TransferStatus status);
}
