package com.paytm.wallet.controller;

import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.dto.WalletResponse;
import com.paytm.wallet.exception.NotFoundException;
import com.paytm.wallet.security.CallerContext;
import com.paytm.wallet.service.WalletService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    /**
     * Get-or-create a wallet for the calling user (identified by bearer token).
     * Returns 200 whether the wallet was just created or already existed —
     * callers only need the wallet data, not creation-vs-lookup semantics.
     */
    @PostMapping
    public ResponseEntity<WalletResponse> createOrGetWallet() {
        String callerId = CallerContext.currentUserId();
        Wallet wallet = walletService.getOrCreateWallet(callerId);
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable long id) {
        return walletService.getWallet(id)
                .map(WalletResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException("Wallet " + id + " not found"));
    }
}
