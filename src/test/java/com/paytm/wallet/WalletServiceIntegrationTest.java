package com.paytm.wallet;

import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Postgres via Testcontainers — the race-free get-or-create mechanism
 * depends on genuine unique-constraint contention, which an in-memory or
 * mocked datasource can't reproduce.
 */
@Testcontainers
@SpringBootTest
class WalletServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WalletService walletService;

    @Test
    void sequentialSameUser_returnsSameWallet() {
        String userId = "user-" + UUID.randomUUID();

        Wallet first = walletService.getOrCreateWallet(userId);
        Wallet second = walletService.getOrCreateWallet(userId);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.balancePaise()).isEqualTo(0L);
    }

    @Test
    void differentUsers_createDistinctWallets() {
        Wallet a = walletService.getOrCreateWallet("user-" + UUID.randomUUID());
        Wallet b = walletService.getOrCreateWallet("user-" + UUID.randomUUID());

        assertThat(a.id()).isNotEqualTo(b.id());
    }

    @Test
    void concurrentSameUser_createsExactlyOneWallet() throws Exception {
        String userId = "user-" + UUID.randomUUID();
        int concurrency = 25;

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Long>> futures = IntStream.range(0, concurrency)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return walletService.getOrCreateWallet(userId).id();
                    }))
                    .collect(Collectors.toList());

            start.countDown();

            Set<Long> distinctIds = futures.stream()
                    .map(f -> {
                        try {
                            return f.get(15, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toSet());

            assertThat(distinctIds).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
