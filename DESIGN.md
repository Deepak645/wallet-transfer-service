# Wallet & P2P Transfer — Design

## 1. Data Model

Two tables are created through Flyway migrations
(`V1__init_schema.sql` and `V2__wallet_transfer_constraints.sql`).

### `wallets`

- `id` — `BIGSERIAL` primary key
- `user_id` — `VARCHAR`, `UNIQUE`
- `balance_paise` — `BIGINT`, `CHECK (balance_paise >= 0)`
- `created_at`
- `updated_at`

### `transfers`

- `id` — `BIGSERIAL` primary key
- `from_wallet_id` — `BIGINT`, foreign key to `wallets(id)`
- `to_wallet_id` — `BIGINT`, foreign key to `wallets(id)`
- `amount_paise` — `BIGINT`
- `idempotency_key` — `VARCHAR`, `UNIQUE`
- `status` — `PENDING`, `COMPLETED`, or `DECLINED`
- timestamps

Indexes exist on both wallet foreign keys.

A transfer references its wallets by foreign key and does not cache wallet
balances. Money is represented as integer paise rather than floating-point or
decimal rupee values, allowing exact integer arithmetic for debit, credit, and
conservation.

## 2. Transfer Atomicity and Concurrency

`TransferServiceImpl.createTransfer` runs as one transactional operation.

The transfer flow is:

1. Lock both wallet rows using `SELECT ... FOR UPDATE`.
2. Acquire the locks in ascending wallet-ID order, independent of transfer
   direction.
3. Check whether the source wallet has sufficient balance.
4. If insufficient, decline the transfer without modifying either wallet.
5. If sufficient, debit the source and credit the destination.
6. Persist the transfer and idempotency state in the same transaction.
7. Commit the complete operation.

There is no partial-apply path: the debit, credit, and transfer state either
commit together or roll back together.

### Deadlock avoidance

Deterministic lock ordering prevents the classic opposite-direction case:

```text
A -> B
B -> A
```

Both transactions acquire the lower wallet ID first, so they cannot acquire
the two wallet locks in opposite orders.

This was verified during testing rather than assumed. An earlier implementation
claimed the idempotency key before explicitly locking the wallets. PostgreSQL's
foreign-key enforcement could then acquire implicit `FOR KEY SHARE` locks in
`from_wallet_id` / `to_wallet_id` order, allowing the same opposite-direction
deadlock to occur.

The fix was to explicitly lock both wallets in ascending ID order before the
idempotency-claim insert. The concurrency test reproduced the deadlock before
the fix and passed repeatedly after the fix.

### Alternatives considered

- **Conditional `UPDATE ... WHERE balance >= amount`** — possible, but the
  explicit two-wallet locking approach makes the concurrency behavior more
  direct and auditable.
- **`SKIP LOCKED`** — not appropriate because a contended money transfer must
  wait for the required lock rather than silently skip the operation.
- **`SERIALIZABLE` isolation with retries** — stronger than necessary for this
  design and adds retry complexity.
- **Application/distributed locking** — unnecessary because PostgreSQL is the
  authoritative state store and already provides the required row-level
  locking.

These alternatives are not implemented.

## 3. Get-or-Create Wallet

`POST /wallets` uses:

```sql
INSERT ... ON CONFLICT (user_id) DO NOTHING RETURNING ...
```

backed by the database `UNIQUE(user_id)` constraint.

If the insert succeeds, the new wallet is returned. If another concurrent
request wins the same unique key, the losing request performs a follow-up
`SELECT` by `user_id` after the conflicting insert has resolved.

This avoids the race in a check-then-insert approach, where two concurrent
requests could both observe that a wallet does not exist before either
request inserts it.

The database constraint is the final authority that guarantees one wallet
per user.

## 4. Idempotency

`transfers.idempotency_key` has a database `UNIQUE` constraint.

A transfer request attempts to claim the idempotency key with:

```sql
INSERT ... ON CONFLICT (idempotency_key) DO NOTHING
```

The idempotency claim, wallet locking, balance movement, and final transfer
state are part of the same database transaction.

The behavior is:

- **Same key + same request body** → return the original transfer result.
- **Same key + different request body** → return `409 Conflict`.
- **Concurrent requests with the same key** → the database uniqueness
  constraint allows only one request to perform the balance movement.

Keeping the idempotency claim in the same transaction as the wallet movement
avoids a split-brain state where an idempotency record could be committed
without the corresponding money movement, or vice versa.

## 5. Consistency vs Availability

Correctness is prioritized over availability for monetary operations.

PostgreSQL is the authoritative source of truth. If the database is
unavailable, a transfer fails rather than being accepted into a queue and
applied later.

An accepted write that is not durably recorded could result in lost or
duplicated money. For this system, failing the operation is preferable to
accepting a transfer without durable state.

There is no write-behind cache or eventual-consistency path for wallet
balances or transfers.

## 6. Observability

The application provides structured JSON logs, correlation IDs, domain
events, metrics, and health information.

### Correlation

Each request receives a correlation ID through `CorrelationIdFilter`.

The ID can also be supplied through the `X-Correlation-Id` header and is
available through MDC for structured logging.

### Domain events

The application records events including:

- `wallet.created`
- `wallet.existing`
- `wallet.test_funded`
- `transfer.created`
- `transfer.debited`
- `transfer.credited`
- `transfer.completed`
- `transfer.declined`
- `transfer.idempotent_replay`

Transaction-dependent events are emitted after the transaction commits, so
events describing wallet or transfer state changes are not emitted for a
transaction that subsequently rolls back.

### Metrics

Micrometer counters cover wallet creation, existing-wallet requests,
test funding, completed transfers, insufficient-funds declines, and
idempotent replays.

HTTP request metrics include request rate, latency, and errors. Histogram
buckets are enabled for `http.server.requests`, allowing p99 latency to be
calculated from the Prometheus metrics.

Metrics are exposed through:

```text
GET /metrics
```

Health information is exposed through:

```text
GET /health
```

## 7. Test Funding Endpoint

`POST /test/wallets/{id}/fund` exists only as test infrastructure because the
assignment's live concurrency probes require wallets with a balance while
the assignment itself does not define a funding mechanism.

The endpoint:

- is disabled by default
- requires a separate bearer token when enabled
- uses the existing wallet row lock
- does not modify the transfer locking or idempotency design
- is not a production deposit or payment feature

The core transfer invariants — conservation, no overdraft, and exactly-once
processing — remain enforced by the transaction, locking, and database
constraints described above.

The endpoint uses `SELECT ... FOR UPDATE` on the target wallet so that a
concurrent funding operation and transfer cannot cause a lost update.

## 8. Cost

The exercise uses free-tier hosting with a containerized application and
managed PostgreSQL.

**Expected cost: ₹0**, assuming the free-tier limits are not exceeded.
