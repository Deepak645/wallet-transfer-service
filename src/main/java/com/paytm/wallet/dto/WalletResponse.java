package com.paytm.wallet.dto;

import com.paytm.wallet.domain.Wallet;

import java.time.Instant;

public record WalletResponse(
        Long id,
        String userId,
        long balancePaise,
        Instant createdAt,
        Instant updatedAt
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.id(),
                wallet.userId(),
                wallet.balancePaise(),
                wallet.createdAt(),
                wallet.updatedAt()
        );
    }
}
