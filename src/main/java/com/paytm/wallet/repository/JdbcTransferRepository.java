package com.paytm.wallet.repository;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.TransferStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class JdbcTransferRepository implements TransferRepository {

    private static final RowMapper<Transfer> TRANSFER_ROW_MAPPER = (rs, rowNum) -> new Transfer(
            rs.getLong("id"),
            rs.getLong("from_wallet_id"),
            rs.getLong("to_wallet_id"),
            rs.getLong("amount_paise"),
            rs.getString("idempotency_key"),
            TransferStatus.valueOf(rs.getString("status")),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant()
    );

    private final JdbcClient jdbcClient;

    public JdbcTransferRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<Transfer> findById(long id) {
        return jdbcClient.sql("""
                        SELECT id, from_wallet_id, to_wallet_id, amount_paise, idempotency_key, status, created_at, updated_at
                        FROM transfers
                        WHERE id = :id
                        """)
                .param("id", id)
                .query(TRANSFER_ROW_MAPPER)
                .optional();
    }

    @Override
    public Optional<Transfer> findByIdempotencyKey(String idempotencyKey) {
        return jdbcClient.sql("""
                        SELECT id, from_wallet_id, to_wallet_id, amount_paise, idempotency_key, status, created_at, updated_at
                        FROM transfers
                        WHERE idempotency_key = :key
                        """)
                .param("key", idempotencyKey)
                .query(TRANSFER_ROW_MAPPER)
                .optional();
    }

    @Override
    public Optional<Transfer> insertPending(long fromWalletId, long toWalletId, long amountPaise, String idempotencyKey) {
        return jdbcClient.sql("""
                        INSERT INTO transfers (from_wallet_id, to_wallet_id, amount_paise, idempotency_key, status)
                        VALUES (:from, :to, :amount, :key, 'PENDING')
                        ON CONFLICT (idempotency_key) DO NOTHING
                        RETURNING id, from_wallet_id, to_wallet_id, amount_paise, idempotency_key, status, created_at, updated_at
                        """)
                .param("from", fromWalletId)
                .param("to", toWalletId)
                .param("amount", amountPaise)
                .param("key", idempotencyKey)
                .query(TRANSFER_ROW_MAPPER)
                .optional();
    }

    @Override
    public void updateStatus(long id, TransferStatus status) {
        jdbcClient.sql("""
                        UPDATE transfers
                        SET status = :status, updated_at = now()
                        WHERE id = :id
                        """)
                .param("status", status.name())
                .param("id", id)
                .update();
    }
}
