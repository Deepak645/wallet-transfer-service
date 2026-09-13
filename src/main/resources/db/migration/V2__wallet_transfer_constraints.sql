-- Adds the constraints that were deliberately deferred in V1, now that the
-- concurrency/idempotency design has been decided:
--   * race-free get-or-create        -> UNIQUE(wallets.user_id)
--   * idempotent transfer creation   -> UNIQUE(transfers.idempotency_key)
--   * no-overdraft                   -> CHECK(wallets.balance_paise >= 0)
--
-- Each UNIQUE constraint also creates its own index, which is what backs
-- the ON CONFLICT targets used by the get-or-create and idempotency-claim
-- queries.

ALTER TABLE wallets
    ADD CONSTRAINT uq_wallets_user_id UNIQUE (user_id);

ALTER TABLE wallets
    ADD CONSTRAINT chk_wallets_balance_non_negative CHECK (balance_paise >= 0);

ALTER TABLE transfers
    ADD CONSTRAINT uq_transfers_idempotency_key UNIQUE (idempotency_key);
