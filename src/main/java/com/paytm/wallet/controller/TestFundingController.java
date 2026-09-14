package com.paytm.wallet.controller;

import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.dto.FundWalletRequest;
import com.paytm.wallet.dto.WalletResponse;
import com.paytm.wallet.service.TestFundingService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEST INFRASTRUCTURE ONLY - not a production payment/deposit feature, and
 * not part of the wallet/transfer API contract. Exists solely so the
 * assignment's live concurrency probes (which need wallets with a real
 * starting balance) can be reproduced through the public HTTP API, since
 * the assignment itself defines no deposit/funding mechanism. See README
 * "Test funding endpoint".
 *
 * This bean - and therefore the whole {@code /test/wallets/{id}/fund} route
 * - only exists when {@code test.funding.enabled=true}
 * ({@code TEST_FUNDING_ENABLED} env var). When that property isn't "true",
 * Spring never registers this controller, so the route has no mapping at
 * all and any request to it gets a plain 404 from Spring's own dispatcher -
 * no custom "disabled" branch needed. When it does exist, requests must
 * still pass {@link com.paytm.wallet.security.TestFundingAuthFilter}
 * (registered under the same condition, in FilterConfig), which checks
 * {@code Authorization: Bearer <TEST_FUNDING_TOKEN>}.
 */
@RestController
@RequestMapping("/test/wallets")
@ConditionalOnProperty(prefix = "test.funding", name = "enabled", havingValue = "true")
public class TestFundingController {

    private final TestFundingService testFundingService;

    public TestFundingController(TestFundingService testFundingService) {
        this.testFundingService = testFundingService;
    }

    @PostMapping("/{id}/fund")
    public ResponseEntity<WalletResponse> fund(@PathVariable long id, @Valid @RequestBody FundWalletRequest request) {
        Wallet wallet = testFundingService.fundWallet(id, request.amountPaise());
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }
}
