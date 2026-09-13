package com.paytm.wallet.service;

import com.paytm.wallet.domain.Wallet;

import java.util.Optional;

public interface WalletService {

    /**
     * Get-or-create semantics for a user's wallet. Not implemented yet —
     * the race-free mechanism (unique constraint + insert-on-conflict vs.
     * check-then-insert, etc.) is a pending design decision.
     */
    Wallet getOrCreateWallet(String userId);

    Optional<Wallet> getWallet(long id);
}
