package com.paytm.wallet.service;

import com.paytm.wallet.domain.Wallet;

/**
 * TEST INFRASTRUCTURE ONLY - see README "Test funding endpoint" and
 * {@code TestFundingServiceImpl}'s class Javadoc. This exists solely so the
 * assignment's live concurrency probes (which need wallets with a real
 * balance) can be reproduced through the public HTTP API, since the
 * assignment itself defines no deposit/funding mechanism. It is not a
 * production payment/deposit feature and has no bearing on the transfer
 * correctness mechanism implemented in {@link TransferServiceImpl}.
 */
public interface TestFundingService {

    /**
     * Atomically increases the wallet's balance by amountPaise. Throws
     * {@link com.paytm.wallet.exception.NotFoundException} if the wallet
     * doesn't exist.
     */
    Wallet fundWallet(long walletId, long amountPaise);
}
