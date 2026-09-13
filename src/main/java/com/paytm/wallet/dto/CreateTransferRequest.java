package com.paytm.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Shape only — field-level validation here is basic input hygiene, not the
 * idempotency/overdraft/locking semantics, which are implemented in
 * {@link com.paytm.wallet.service.TransferServiceImpl}.
 *
 * Wire format is snake_case per the assignment spec (amount_paise,
 * idempotency_key); Java fields stay camelCase for the rest of the
 * codebase, mapped explicitly per-field rather than via a global Jackson
 * naming strategy (which would also affect every other DTO's wire format).
 */
public record CreateTransferRequest(
        @NotNull Long from,
        @NotNull Long to,
        @Positive @JsonProperty("amount_paise") long amountPaise,
        @NotBlank @JsonProperty("idempotency_key") String idempotencyKey
) {
}
