package com.paytm.wallet.dto;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.TransferStatus;

import java.time.Instant;

public record TransferResponse(
        Long id,
        Long fromWalletId,
        Long toWalletId,
        long amountPaise,
        String idempotencyKey,
        TransferStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.id(),
                transfer.fromWalletId(),
                transfer.toWalletId(),
                transfer.amountPaise(),
                transfer.idempotencyKey(),
                transfer.status(),
                transfer.createdAt(),
                transfer.updatedAt()
        );
    }
}
