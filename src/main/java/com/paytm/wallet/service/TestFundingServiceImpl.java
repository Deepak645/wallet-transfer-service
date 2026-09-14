package com.paytm.wallet.service;

import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.exception.NotFoundException;
import com.paytm.wallet.repository.WalletRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * TEST INFRASTRUCTURE ONLY. Deliberately kept separate from
 * {@link WalletServiceImpl} (which implements the real get-or-create API)
 * so this test-only concern stays clearly isolated rather than blended into
 * production wallet logic.
 *
 * Reuses the exact same locking primitive the real transfer path uses
 * ({@link WalletRepository#lockById}, the same {@code SELECT ... FOR UPDATE}
 * row lock TransferServiceImpl takes) so a concurrent fund + transfer on the
 * same wallet can't race into a lost update. This is the only concurrency
 * consideration here - there is no idempotency key, no multi-wallet
 * ordering, and no conservation invariant to enforce, because this is a
 * single-row test fixture write, not a transfer.
 */
@Service
public class TestFundingServiceImpl implements TestFundingService {

    private static final Logger log = LoggerFactory.getLogger(TestFundingServiceImpl.class);

    private final WalletRepository walletRepository;
    private final MeterRegistry meterRegistry;

    public TestFundingServiceImpl(WalletRepository walletRepository, MeterRegistry meterRegistry) {
        this.walletRepository = walletRepository;
        this.meterRegistry = meterRegistry;
    }

    @Override
    @Transactional
    public Wallet fundWallet(long walletId, long amountPaise) {
        Wallet wallet = walletRepository.lockById(walletId)
                .orElseThrow(() -> new NotFoundException("Wallet " + walletId + " not found"));

        long newBalance = wallet.balancePaise() + amountPaise;
        walletRepository.updateBalance(walletId, newBalance);

        afterCommit(() -> {
            log.info("wallet test funded",
                    kv("event", "wallet.test_funded"),
                    kv("walletId", walletId),
                    kv("amountPaise", amountPaise),
                    kv("balancePaise", newBalance));
            meterRegistry.counter("wallets.test_funded").increment();
        });

        return walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalStateException("Wallet " + walletId + " vanished mid-transaction"));
    }

    /**
     * Same afterCommit pattern as TransferServiceImpl (duplicated rather
     * than extracted into a shared helper, to avoid touching that file at
     * all for this test-only addition). Ensures the log/counter can never
     * fire for a funding write that later rolled back.
     */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
