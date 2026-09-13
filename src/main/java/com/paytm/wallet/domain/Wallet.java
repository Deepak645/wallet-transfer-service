package com.paytm.wallet.domain;

import java.time.Instant;

/**
 * A wallet's balance is authoritative in the {@code wallets} table only.
 * No read-modify-write helpers live here on purpose: how balance is mutated
 * (conditional UPDATE, SELECT FOR UPDATE, etc.) is a pending design decision.
 */
public record Wallet(
        Long id,
        String userId,
        long balancePaise,
        Instant createdAt,
        Instant updatedAt
) {
}
