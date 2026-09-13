package com.paytm.wallet.repository;

import com.paytm.wallet.domain.Wallet;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class JdbcWalletRepository implements WalletRepository {

    private static final RowMapper<Wallet> WALLET_ROW_MAPPER = (rs, rowNum) -> new Wallet(
            rs.getLong("id"),
            rs.getString("user_id"),
            rs.getLong("balance_paise"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant()
    );

    private final JdbcClient jdbcClient;

    public JdbcWalletRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<Wallet> findById(long id) {
        return jdbcClient.sql("""
                        SELECT id, user_id, balance_paise, created_at, updated_at
                        FROM wallets
                        WHERE id = :id
                        """)
                .param("id", id)
                .query(WALLET_ROW_MAPPER)
                .optional();
    }

    @Override
    public Optional<Wallet> findByUserId(String userId) {
        return jdbcClient.sql("""
                        SELECT id, user_id, balance_paise, created_at, updated_at
                        FROM wallets
                        WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .query(WALLET_ROW_MAPPER)
                .optional();
    }

    @Override
    public Optional<Wallet> insertIfAbsent(String userId) {
        return jdbcClient.sql("""
                        INSERT INTO wallets (user_id, balance_paise)
                        VALUES (:userId, 0)
                        ON CONFLICT (user_id) DO NOTHING
                        RETURNING id, user_id, balance_paise, created_at, updated_at
                        """)
                .param("userId", userId)
                .query(WALLET_ROW_MAPPER)
                .optional();
    }

    @Override
    public Optional<Wallet> lockById(long id) {
        return jdbcClient.sql("""
                        SELECT id, user_id, balance_paise, created_at, updated_at
                        FROM wallets
                        WHERE id = :id
                        FOR UPDATE
                        """)
                .param("id", id)
                .query(WALLET_ROW_MAPPER)
                .optional();
    }

    @Override
    public void updateBalance(long id, long newBalancePaise) {
        jdbcClient.sql("""
                        UPDATE wallets
                        SET balance_paise = :balance, updated_at = now()
                        WHERE id = :id
                        """)
                .param("balance", newBalancePaise)
                .param("id", id)
                .update();
    }
}
