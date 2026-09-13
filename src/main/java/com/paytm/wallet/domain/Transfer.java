package com.paytm.wallet.domain;

import java.time.Instant;

public record Transfer(
        Long id,
        Long fromWalletId,
        Long toWalletId,
        long amountPaise,
        String idempotencyKey,
        TransferStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
