package com.paytm.wallet.service;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.dto.CreateTransferRequest;

import java.util.Optional;

public interface TransferService {

    /**
     * Not implemented yet — depends on the concurrency-control mechanism,
     * where idempotency is enforced, and overdraft handling, none of which
     * have been decided.
     */
    Transfer createTransfer(String callerId, CreateTransferRequest request);

    Optional<Transfer> getTransfer(long id);
}
