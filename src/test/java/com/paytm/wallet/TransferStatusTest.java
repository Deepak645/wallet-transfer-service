package com.paytm.wallet;

import com.paytm.wallet.domain.TransferStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A trivial, DB-free test so `mvn test` proves the toolchain works without
 * requiring a live Postgres instance. Full integration tests (against the
 * docker-compose Postgres) belong here once the business logic is decided.
 */
class TransferStatusTest {

    @Test
    void hasExpectedProvisionalValues() {
        assertThat(TransferStatus.values()).containsExactlyInAnyOrder(
                TransferStatus.PENDING,
                TransferStatus.COMPLETED,
                TransferStatus.DECLINED,
                TransferStatus.FAILED
        );
    }
}
