package com.paytm.wallet.domain;

/**
 * Provisional status values only. The state machine (which transitions are
 * legal, what "declined" vs "failed" means) is part of the pending
 * concurrency/business-logic design and may change.
 */
public enum TransferStatus {
    PENDING,
    COMPLETED,
    DECLINED,
    FAILED
}
