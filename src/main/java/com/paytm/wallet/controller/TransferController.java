package com.paytm.wallet.controller;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.dto.CreateTransferRequest;
import com.paytm.wallet.dto.TransferResponse;
import com.paytm.wallet.exception.NotFoundException;
import com.paytm.wallet.security.CallerContext;
import com.paytm.wallet.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * Returns 200 for every successfully-processed request — a freshly
     * created transfer (COMPLETED or DECLINED) and an idempotent replay of
     * an existing one both return the transfer resource the same way; only
     * the resource's own `status` field distinguishes them. A same-key/
     * different-body replay is the one case that isn't 200 (409, via
     * IdempotencyConflictException).
     */
    @PostMapping
    public ResponseEntity<TransferResponse> createTransfer(@Valid @RequestBody CreateTransferRequest request) {
        String callerId = CallerContext.currentUserId();
        Transfer transfer = transferService.createTransfer(callerId, request);
        return ResponseEntity.ok(TransferResponse.from(transfer));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransferResponse> getTransfer(@PathVariable long id) {
        return transferService.getTransfer(id)
                .map(TransferResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException("Transfer " + id + " not found"));
    }
}
