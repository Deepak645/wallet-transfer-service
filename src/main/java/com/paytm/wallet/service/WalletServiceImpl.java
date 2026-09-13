package com.paytm.wallet.service;

import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class WalletServiceImpl implements WalletService {

    private final WalletRepository walletRepository;

    public WalletServiceImpl(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
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
            return inserted.get();
        }
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "insertIfAbsent conflicted for user " + userId + " but no existing row was found"));
    }

    @Override
    public Optional<Wallet> getWallet(long id) {
        return walletRepository.findById(id);
    }
}
