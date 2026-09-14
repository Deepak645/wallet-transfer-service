package com.paytm.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Positive;

/**
 * Request body for the TEST-ONLY funding endpoint
 * (see {@code com.paytm.wallet.controller.TestFundingController}). Not part
 * of the real wallet/transfer API contract.
 */
public record FundWalletRequest(
        @Positive @JsonProperty("amount_paise") long amountPaise
) {
}
