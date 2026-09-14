package com.paytm.wallet;

import com.paytm.wallet.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Postgres via Testcontainers, real HTTP via MockMvc - exercises the
 * TEST-ONLY funding endpoint exactly as a burst-test run against a live
 * deployment would. test.funding.enabled=true and a fixture token are set
 * in src/test/resources/application.yml (not a real secret - see its
 * comment).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class TestFundingIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WalletService walletService;
    @Autowired
    private MockMvc mockMvc;
    @Value("${test.funding.token}")
    private String validToken;

    private long createWallet() {
        return walletService.getOrCreateWallet("user-" + UUID.randomUUID()).id();
    }

    private long balanceOf(long walletId) {
        return walletService.getWallet(walletId).orElseThrow().balancePaise();
    }

    @Test
    void fundingValidWallet_returns200WithUpdatedBalance() throws Exception {
        long walletId = createWallet();

        mockMvc.perform(post("/test/wallets/" + walletId + "/fund")
                        .header("Authorization", "Bearer " + validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount_paise\": 100000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(walletId))
                .andExpect(jsonPath("$.balancePaise").value(100000));

        assertThat(balanceOf(walletId)).isEqualTo(100_000L);
    }

    @Test
    void fundingIncreasesBalanceByExactIntegerAmount_noFloatingPointDrift() throws Exception {
        long walletId = createWallet();

        mockMvc.perform(post("/test/wallets/" + walletId + "/fund")
                        .header("Authorization", "Bearer " + validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount_paise\": 5000}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/test/wallets/" + walletId + "/fund")
                        .header("Authorization", "Bearer " + validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount_paise\": 2500}"))
                .andExpect(status().isOk());

        assertThat(balanceOf(walletId)).isEqualTo(7_500L);
    }

    @Test
    void fundingNonexistentWallet_returns404() throws Exception {
        mockMvc.perform(post("/test/wallets/999999999/fund")
                        .header("Authorization", "Bearer " + validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount_paise\": 100}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void fundingZeroAmount_rejectedWith400() throws Exception {
        long walletId = createWallet();

        mockMvc.perform(post("/test/wallets/" + walletId + "/fund")
                        .header("Authorization", "Bearer " + validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount_paise\": 0}"))
                .andExpect(status().isBadRequest());

        assertThat(balanceOf(walletId)).isEqualTo(0L);
    }

    @Test
    void fundingNegativeAmount_rejectedWith400() throws Exception {
        long walletId = createWallet();

        mockMvc.perform(post("/test/wallets/" + walletId + "/fund")
                        .header("Authorization", "Bearer " + validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount_paise\": -500}"))
                .andExpect(status().isBadRequest());

        assertThat(balanceOf(walletId)).isEqualTo(0L);
    }

    @Test
    void fundingWithoutToken_rejectedWith401() throws Exception {
        long walletId = createWallet();

        mockMvc.perform(post("/test/wallets/" + walletId + "/fund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount_paise\": 100}"))
                .andExpect(status().isUnauthorized());

        assertThat(balanceOf(walletId)).isEqualTo(0L);
    }

    @Test
    void fundingWithWrongToken_rejectedWith401() throws Exception {
        long walletId = createWallet();

        mockMvc.perform(post("/test/wallets/" + walletId + "/fund")
                        .header("Authorization", "Bearer wrong-token-entirely")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount_paise\": 100}"))
                .andExpect(status().isUnauthorized());

        assertThat(balanceOf(walletId)).isEqualTo(0L);
    }
}
