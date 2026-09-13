package com.paytm.wallet;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.TransferStatus;
import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.dto.CreateTransferRequest;
import com.paytm.wallet.exception.BadRequestException;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.exception.NotFoundException;
import com.paytm.wallet.service.TransferService;
import com.paytm.wallet.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Postgres via Testcontainers. These tests exercise the actual
 * SELECT ... FOR UPDATE lock ordering, the idempotency-key unique
 * constraint, and the no-overdraft check — none of which can be validated
 * meaningfully without a real database under concurrent load.
 */
@Testcontainers
@SpringBootTest
class TransferServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WalletService walletService;
    @Autowired
    private TransferService transferService;
    @Autowired
    private JdbcClient jdbcClient;

    private Wallet fundedWallet(long amountPaise) {
        Wallet wallet = walletService.getOrCreateWallet("user-" + UUID.randomUUID());
        jdbcClient.sql("UPDATE wallets SET balance_paise = :amount WHERE id = :id")
                .param("amount", amountPaise)
                .param("id", wallet.id())
                .update();
        return wallet;
    }

    private long balanceOf(long walletId) {
        return walletService.getWallet(walletId).orElseThrow().balancePaise();
    }

    @Test
    void sufficientBalance_completesAndMovesMoney() {
        Wallet a = fundedWallet(10_000);
        Wallet b = fundedWallet(0);

        Transfer transfer = transferService.createTransfer("caller",
                new CreateTransferRequest(a.id(), b.id(), 3_000, "key-" + UUID.randomUUID()));

        assertThat(transfer.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(balanceOf(a.id())).isEqualTo(7_000);
        assertThat(balanceOf(b.id())).isEqualTo(3_000);
    }

    @Test
    void insufficientBalance_declinesWithoutChangingBalances() {
        Wallet a = fundedWallet(100);
        Wallet b = fundedWallet(0);

        Transfer transfer = transferService.createTransfer("caller",
                new CreateTransferRequest(a.id(), b.id(), 500, "key-" + UUID.randomUUID()));

        assertThat(transfer.status()).isEqualTo(TransferStatus.DECLINED);
        assertThat(balanceOf(a.id())).isEqualTo(100);
        assertThat(balanceOf(b.id())).isEqualTo(0);
    }

    @Test
    void selfTransfer_rejected() {
        Wallet a = fundedWallet(1_000);

        assertThatThrownBy(() -> transferService.createTransfer("caller",
                new CreateTransferRequest(a.id(), a.id(), 100, "key-" + UUID.randomUUID())))
                .isInstanceOf(BadRequestException.class);
        assertThat(balanceOf(a.id())).isEqualTo(1_000);
    }

    @Test
    void unknownWallet_rejectedAndDoesNotConsumeIdempotencyKey() {
        Wallet a = fundedWallet(1_000);
        String key = "key-" + UUID.randomUUID();

        assertThatThrownBy(() -> transferService.createTransfer("caller",
                new CreateTransferRequest(a.id(), 999_999_999L, 100, key)))
                .isInstanceOf(NotFoundException.class);
        assertThat(balanceOf(a.id())).isEqualTo(1_000);

        // The same key against real wallets afterwards must succeed normally -
        // proof that the failed attempt never claimed the idempotency key.
        Wallet b = fundedWallet(0);
        Transfer retry = transferService.createTransfer("caller",
                new CreateTransferRequest(a.id(), b.id(), 100, key));
        assertThat(retry.status()).isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    void sameKeySameBody_returnsOriginalResultWithoutDoubleMovement() {
        Wallet a = fundedWallet(1_000);
        Wallet b = fundedWallet(0);
        String key = "key-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(a.id(), b.id(), 200, key);

        Transfer first = transferService.createTransfer("caller", request);
        Transfer second = transferService.createTransfer("caller", request);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(balanceOf(a.id())).isEqualTo(800);
        assertThat(balanceOf(b.id())).isEqualTo(200);
    }

    @Test
    void sameKeyDifferentBody_conflict() {
        Wallet a = fundedWallet(1_000);
        Wallet b = fundedWallet(0);
        Wallet c = fundedWallet(0);
        String key = "key-" + UUID.randomUUID();

        transferService.createTransfer("caller", new CreateTransferRequest(a.id(), b.id(), 200, key));

        assertThatThrownBy(() -> transferService.createTransfer("caller",
                new CreateTransferRequest(a.id(), c.id(), 200, key)))
                .isInstanceOf(IdempotencyConflictException.class);

        // The original transfer must be untouched by the rejected replay.
        assertThat(balanceOf(a.id())).isEqualTo(800);
        assertThat(balanceOf(b.id())).isEqualTo(200);
        assertThat(balanceOf(c.id())).isEqualTo(0);
    }

    @Test
    void concurrentSameKey_exactlyOneMovement() throws Exception {
        Wallet a = fundedWallet(10_000);
        Wallet b = fundedWallet(0);
        String key = "key-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(a.id(), b.id(), 1_000, key);

        int concurrency = 20;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return transferService.createTransfer("caller", request).id();
                }));
            }

            start.countDown();
            List<Long> ids = new ArrayList<>();
            for (Future<Long> f : futures) {
                ids.add(f.get(20, TimeUnit.SECONDS));
            }

            assertThat(new HashSet<>(ids)).hasSize(1);
            assertThat(balanceOf(a.id())).isEqualTo(9_000);
            assertThat(balanceOf(b.id())).isEqualTo(1_000);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentOppositeDirections_conserveTotalWithNoDeadlock() throws Exception {
        Wallet a = fundedWallet(50_000);
        Wallet b = fundedWallet(50_000);
        long totalBefore = balanceOf(a.id()) + balanceOf(b.id());

        // Kept modest (not e.g. 40+ per direction): this environment's Docker
        // Desktop VM is memory-constrained enough that a much larger burst of
        // simultaneous new connections crashes the Postgres container itself
        // (all connections fail at once with SQLSTATE 08006/EOFException) -
        // an environment resource limit, not a correctness issue in the
        // locking logic. This scale still genuinely exercises concurrent
        // opposite-direction transfers against the same two wallets.
        int perDirection = 15;
        ExecutorService pool = Executors.newFixedThreadPool(2 * perDirection);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Transfer>> futures = new ArrayList<>();
            for (int i = 0; i < perDirection; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return transferService.createTransfer("caller",
                            new CreateTransferRequest(a.id(), b.id(), 100, "ab-" + idx + "-" + UUID.randomUUID()));
                }));
                futures.add(pool.submit(() -> {
                    start.await();
                    return transferService.createTransfer("caller",
                            new CreateTransferRequest(b.id(), a.id(), 100, "ba-" + idx + "-" + UUID.randomUUID()));
                }));
            }

            start.countDown();
            // .get() re-throws any exception from the task - including a
            // Postgres deadlock-detected error, which would fail this test.
            for (Future<Transfer> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }

            long totalAfter = balanceOf(a.id()) + balanceOf(b.id());
            assertThat(totalAfter).isEqualTo(totalBefore);
            assertThat(balanceOf(a.id())).isGreaterThanOrEqualTo(0);
            assertThat(balanceOf(b.id())).isGreaterThanOrEqualTo(0);
        } finally {
            pool.shutdownNow();
        }
    }
}
