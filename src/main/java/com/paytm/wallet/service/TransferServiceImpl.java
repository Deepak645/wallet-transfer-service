package com.paytm.wallet.service;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.TransferStatus;
import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.dto.CreateTransferRequest;
import com.paytm.wallet.exception.BadRequestException;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.exception.NotFoundException;
import com.paytm.wallet.repository.TransferRepository;
import com.paytm.wallet.repository.WalletRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class TransferServiceImpl implements TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferServiceImpl.class);

    private final TransferRepository transferRepository;
    private final WalletRepository walletRepository;
    private final MeterRegistry meterRegistry;

    public TransferServiceImpl(TransferRepository transferRepository, WalletRepository walletRepository,
                                MeterRegistry meterRegistry) {
        this.transferRepository = transferRepository;
        this.walletRepository = walletRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Concurrency design (fixed, not to be silently changed — see
     * 01-wallet-transfer-exercise.md invariants 1-3):
     *
     * 1. Validate input (from != to).
     * 2. Lock BOTH wallet rows with SELECT ... FOR UPDATE in ascending
     *    wallet-id order (never in from/to order) BEFORE anything else
     *    touches the wallets table in this transaction. This also
     *    doubles as the existence check (empty -> 404).
     *
     *    This ordering is load-bearing, not incidental: `transfers` has
     *    FK columns referencing `wallets(id)`, and Postgres enforces that
     *    by taking an implicit FOR KEY SHARE lock on the referenced wallet
     *    rows at INSERT time, in from/to column order - NOT ascending id
     *    order. If the idempotency-claim INSERT (step 3) ran before we
     *    held our own locks, its FK check would silently reintroduce the
     *    exact opposite-order deadlock this design is supposed to
     *    prevent (confirmed live: concurrent A->B/B->A transfers deadlocked
     *    under this exact scenario during testing). Locking explicitly
     *    first means our transaction already holds the stronger lock when
     *    the FK check runs, so that check is a same-transaction no-op.
     * 3. Claim the idempotency_key via INSERT ... ON CONFLICT DO NOTHING
     *    (status=PENDING). Postgres serializes concurrent claims of the
     *    same key through the unique index, so exactly one caller ever
     *    proceeds to mutate balances for a given key; everyone else reads
     *    the winner's committed result back. Because step 2 already holds
     *    the wallet locks by this point, a losing (duplicate-key) caller
     *    briefly holds those locks without using them - a minor,
     *    acceptable serialization cost for retries/duplicates, not a
     *    correctness issue.
     * 4. Check source balance >= amount only now. Insufficient ->
     *    DECLINED, no balance change. Sufficient -> debit + credit,
     *    COMPLETED. Either way the transfer row is finalized and both
     *    wallet writes (if any) commit in the same transaction as
     *    everything else.
     */
    @Override
    @Transactional
    public Transfer createTransfer(String callerId, CreateTransferRequest request) {
        if (request.from().equals(request.to())) {
            throw new BadRequestException("from and to wallets must be different");
        }

        long lowId = Math.min(request.from(), request.to());
        long highId = Math.max(request.from(), request.to());

        Wallet lowWallet = walletRepository.lockById(lowId)
                .orElseThrow(() -> new NotFoundException("Wallet " + lowId + " not found"));
        Wallet highWallet = walletRepository.lockById(highId)
                .orElseThrow(() -> new NotFoundException("Wallet " + highId + " not found"));

        Optional<Transfer> claimed = transferRepository.insertPending(
                request.from(), request.to(), request.amountPaise(), request.idempotencyKey());

        if (claimed.isEmpty()) {
            Transfer existing = transferRepository.findByIdempotencyKey(request.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "idempotency_key claim conflicted but no existing row was found: " + request.idempotencyKey()));
            if (!sameRequest(existing, request)) {
                throw new IdempotencyConflictException(
                        "idempotency_key '" + request.idempotencyKey() + "' was already used with a different request body");
            }
            log.info("transfer idempotent replay",
                    kv("event", "transfer.idempotent_replay"),
                    kv("transferId", existing.id()),
                    kv("fromWalletId", existing.fromWalletId()),
                    kv("toWalletId", existing.toWalletId()),
                    kv("amountPaise", existing.amountPaise()),
                    kv("idempotencyKey", existing.idempotencyKey()),
                    kv("status", existing.status()));
            meterRegistry.counter("transfers.idempotent_replay").increment();
            return existing;
        }

        Transfer transfer = claimed.get();

        Wallet source = request.from() == lowId ? lowWallet : highWallet;
        Wallet destination = request.from() == lowId ? highWallet : lowWallet;

        if (source.balancePaise() < request.amountPaise()) {
            transferRepository.updateStatus(transfer.id(), TransferStatus.DECLINED);
            log.info("transfer declined",
                    kv("event", "transfer.declined"),
                    kv("transferId", transfer.id()),
                    kv("fromWalletId", request.from()),
                    kv("toWalletId", request.to()),
                    kv("amountPaise", request.amountPaise()),
                    kv("idempotencyKey", request.idempotencyKey()),
                    kv("status", TransferStatus.DECLINED));
            meterRegistry.counter("transfers.declined").increment();
        } else {
            walletRepository.updateBalance(source.id(), source.balancePaise() - request.amountPaise());
            walletRepository.updateBalance(destination.id(), destination.balancePaise() + request.amountPaise());
            transferRepository.updateStatus(transfer.id(), TransferStatus.COMPLETED);
            log.info("transfer completed",
                    kv("event", "transfer.completed"),
                    kv("transferId", transfer.id()),
                    kv("fromWalletId", request.from()),
                    kv("toWalletId", request.to()),
                    kv("amountPaise", request.amountPaise()),
                    kv("idempotencyKey", request.idempotencyKey()),
                    kv("status", TransferStatus.COMPLETED));
            meterRegistry.counter("transfers.completed").increment();
        }

        return transferRepository.findById(transfer.id())
                .orElseThrow(() -> new IllegalStateException("Transfer " + transfer.id() + " vanished mid-transaction"));
    }

    private boolean sameRequest(Transfer existing, CreateTransferRequest request) {
        return existing.fromWalletId().equals(request.from())
                && existing.toWalletId().equals(request.to())
                && existing.amountPaise() == request.amountPaise();
    }

    @Override
    public Optional<Transfer> getTransfer(long id) {
        return transferRepository.findById(id);
    }
}
