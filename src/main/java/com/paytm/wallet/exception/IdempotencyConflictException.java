package com.paytm.wallet.exception;

/**
 * Same idempotency_key reused with a different request body. Maps to 409.
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
