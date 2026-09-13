package com.paytm.wallet.exception;

/**
 * Thrown by endpoints whose business logic depends on a design decision
 * that hasn't been made yet (concurrency mechanism, idempotency placement,
 * overdraft handling, etc). Maps to HTTP 501.
 */
public class NotImplementedException extends RuntimeException {
    public NotImplementedException(String message) {
        super(message);
    }
}
