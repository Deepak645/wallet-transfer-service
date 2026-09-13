package com.paytm.wallet;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.service.WalletService;
import com.paytm.wallet.service.WalletServiceImpl;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
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
import static org.hamcrest.Matchers.containsString;

/**
 * Real Postgres via Testcontainers — the race-free get-or-create mechanism
 * depends on genuine unique-constraint contention, which an in-memory or
 * mocked datasource can't reproduce.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class WalletServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WalletService walletService;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private MockMvc mockMvc;

    private double counterValue(String name) {
        Counter counter = meterRegistry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static boolean hasKv(ILoggingEvent event, String key, String value) {
        String expected = key + "=" + value;
        return Arrays.stream(event.getArgumentArray()).anyMatch(arg -> expected.equals(String.valueOf(arg)));
    }

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

    @Test
    void getOrCreateWallet_incrementsCreatedCounterForNewWallet() {
        double before = counterValue("wallets.create");

        walletService.getOrCreateWallet("user-" + UUID.randomUUID());

        assertThat(counterValue("wallets.create")).isEqualTo(before + 1);
    }

    /**
     * Checking the internal Micrometer registry by name (as the test above
     * does) isn't sufficient on its own: Prometheus/OpenMetrics treats a
     * literal "_created" name suffix as reserved (the auto-generated
     * counter-creation-timestamp series), so a counter registered as
     * "wallets.created" would silently export as a bare "wallets_total"
     * instead of "wallets_created_total" - the internal registry lookup
     * would still find it fine, masking the problem. This test caught that
     * exact bug live and is why the counter is named "wallets.create".
     */
    @Test
    void metricsEndpoint_exposesWalletDomainCountersUnderExpectedNames() throws Exception {
        walletService.getOrCreateWallet("user-" + UUID.randomUUID());

        mockMvc.perform(get("/metrics"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("wallets_create_total")))
                .andExpect(content().string(containsString("wallets_existing_total")));
    }

    @Test
    void getOrCreateWallet_incrementsExistingCounterForRepeatCall() {
        String userId = "user-" + UUID.randomUUID();
        walletService.getOrCreateWallet(userId);
        double before = counterValue("wallets.existing");

        walletService.getOrCreateWallet(userId);

        assertThat(counterValue("wallets.existing")).isEqualTo(before + 1);
    }

    @Test
    void getOrCreateWallet_emitsDomainLogEvents() {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(WalletServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            String userId = "user-" + UUID.randomUUID();
            walletService.getOrCreateWallet(userId);
            walletService.getOrCreateWallet(userId);

            assertThat(appender.list).anyMatch(e ->
                    "wallet created".equals(e.getFormattedMessage()) && hasKv(e, "event", "wallet.created"));
            assertThat(appender.list).anyMatch(e ->
                    "wallet existing".equals(e.getFormattedMessage()) && hasKv(e, "event", "wallet.existing"));
        } finally {
            logbackLogger.detachAppender(appender);
        }
    }
}
