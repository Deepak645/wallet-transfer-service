package com.paytm.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Shape only — field-level validation here is basic input hygiene, not the
 * idempotency/overdraft/locking semantics, which are still pending.
 */
public record CreateTransferRequest(
        @NotNull Long from,
        @NotNull Long to,
        @Positive long amountPaise,
        @NotBlank String idempotencyKey
) {
}
