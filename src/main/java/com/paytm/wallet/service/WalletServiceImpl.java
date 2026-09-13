package com.paytm.wallet.service;

import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.repository.WalletRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class WalletServiceImpl implements WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletServiceImpl.class);

    private final WalletRepository walletRepository;
    private final MeterRegistry meterRegistry;

    public WalletServiceImpl(WalletRepository walletRepository, MeterRegistry meterRegistry) {
        this.walletRepository = walletRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Race-free get-or-create: try INSERT ... ON CONFLICT (user_id) DO
     * NOTHING first. If it wins, we're done. If it loses (a wallet for this
     * user already existed, or a concurrent request is inserting it right
     * now), Postgres will have made our INSERT wait for that other
     * transaction to resolve before reporting the conflict — so by the time
     * we get here the other row is guaranteed to be visible, and a plain
     * SELECT finds it. No check-then-insert race window.
     */
    @Override
    @Transactional
    public Wallet getOrCreateWallet(String userId) {
        Optional<Wallet> inserted = walletRepository.insertIfAbsent(userId);
        if (inserted.isPresent()) {
            Wallet wallet = inserted.get();
            log.info("wallet created",
                    kv("event", "wallet.created"),
                    kv("walletId", wallet.id()),
                    kv("userId", wallet.userId()));
            // Named "wallets.create", not "wallets.created": Prometheus/
            // OpenMetrics treats a literal "_created" suffix as reserved (the
            // auto-generated counter-creation-timestamp series), so Micrometer's
            // Prometheus naming convention silently collapses "wallets_created_total"
            // down to a bare "wallets_total" - confirmed live against a running
            // /metrics scrape. Dropping the trailing "d" avoids the collision
            // while keeping the name self-explanatory.
            meterRegistry.counter("wallets.create").increment();
            return wallet;
        }
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "insertIfAbsent conflicted for user " + userId + " but no existing row was found"));
        log.info("wallet existing",
                kv("event", "wallet.existing"),
                kv("walletId", wallet.id()),
                kv("userId", wallet.userId()));
        meterRegistry.counter("wallets.existing").increment();
        return wallet;
    }

    @Override
    public Optional<Wallet> getWallet(long id) {
        return walletRepository.findById(id);
    }
}
