-- Initial schema for wallets & transfers.
--
-- The concurrency-relevant constraints (UNIQUE on user_id / idempotency_key,
-- CHECK on balance_paise >= 0) were deliberately deferred out of this
-- migration while those designs were still pending; they now live in
-- V2__wallet_transfer_constraints.sql instead of being back-ported here,
-- since this file has already been applied against dev databases and
-- Flyway checksums migrations it has run.

CREATE TABLE wallets (
    id             BIGSERIAL PRIMARY KEY,
    user_id        VARCHAR(255) NOT NULL,
    balance_paise  BIGINT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transfers (
    id               BIGSERIAL PRIMARY KEY,
    from_wallet_id   BIGINT NOT NULL REFERENCES wallets(id),
    to_wallet_id     BIGINT NOT NULL REFERENCES wallets(id),
    amount_paise     BIGINT NOT NULL,
    idempotency_key  VARCHAR(255) NOT NULL,
    status           VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_transfers_from_wallet_id ON transfers(from_wallet_id);
CREATE INDEX idx_transfers_to_wallet_id ON transfers(to_wallet_id);
