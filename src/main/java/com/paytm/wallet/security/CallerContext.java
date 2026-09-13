package com.paytm.wallet.security;

/**
 * Holds the caller identity extracted from the bearer token for the current
 * request thread. Cleared unconditionally at the end of every request by
 * {@link BearerAuthFilter}.
 */
public final class CallerContext {

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    private CallerContext() {
    }

    static void set(String userId) {
        CURRENT_USER.set(userId);
    }

    static void clear() {
        CURRENT_USER.remove();
    }

    public static String currentUserId() {
        return CURRENT_USER.get();
    }
}
