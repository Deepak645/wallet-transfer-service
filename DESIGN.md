# Wallet & P2P Transfer — Design

## 1. Data Model

Two tables (Flyway `V1__init_schema.sql`, `V2__wallet_transfer_constraints.sql`):

**`wallets`**: `id` (BIGSERIAL PK), `user_id` (VARCHAR, **UNIQUE**), `balance_paise` (BIGINT, **CHECK >= 0**), `created_at`, `updated_at`.

**`transfers`**: `id` (BIGSERIAL PK), `from_wallet_id` / `to_wallet_id` (BIGINT, FK → `wallets(id)`), `amount_paise` (BIGINT), `idempotency_key` (VARCHAR, **UNIQUE**), `status` (`PENDING`/`COMPLETED`/`DECLINED`), timestamps. Indexed on both wallet FKs.

A transfer references its wallets by foreign key only — it never caches balances. Money is **integer paise**, never floats/decimal rupees, so debit, credit, and conservation checks are exact integer arithmetic with no rounding error.

## 2. Transfer Atomicity and Concurrency

`TransferServiceImpl.createTransfer` runs as one `@Transactional` method. Both wallet rows are locked with `SELECT ... FOR UPDATE` in **ascending wallet-id order** (`Math.min`/`Math.max` of the two ids, independent of transfer direction). Only once both locks are held is the balance checked: insufficient balance declines with no write to either wallet; sufficient balance debits and credits in the same transaction before commit. There is no partial-apply path.

Deterministic ordering exists so a concurrent `A→B` and `B→A` can't deadlock: both request the same first lock (the lower id) before the second, so neither can hold-and-wait on the other in reverse order. This was verified, not assumed — an earlier version locked the idempotency-key row first, and Postgres's implicit `FOR KEY SHARE` lock on FK-referenced wallets (taken in `from`/`to` order at insert time) reintroduced the same deadlock. Locking the wallets explicitly *before* that insert closed the gap; a concurrency test reproduced the deadlock before the fix and passed after.

Rejected alternatives: a conditional `UPDATE ... WHERE balance >= amount` (no explicit locking) was considered but not used, in favor of the more auditable two-lock approach. `SKIP LOCKED` was explicitly excluded — a contended transfer must wait, not silently no-op. `SERIALIZABLE` isolation with retry was rejected as heavier than needed. Application-level or distributed locking is unnecessary with a single authoritative Postgres. None of these alternatives are implemented.

## 3. Get-or-Create Wallet

`POST /wallets` runs `INSERT ... ON CONFLICT (user_id) DO NOTHING RETURNING ...`, backed by `UNIQUE(user_id)`. A winning insert returns that row; a losing one is guaranteed the winner has already committed (Postgres serializes concurrent inserts on the same key), so a follow-up `SELECT` by `user_id` always finds it — no race window. Check-then-insert is race-prone because two concurrent requests can both pass the existence check before either inserts, producing two wallets for one user.

## 4. Idempotency

`transfers.idempotency_key` is `UNIQUE`. A request first attempts `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING` (status `PENDING`) in the same transaction that later debits/credits and finalizes the status. A winning insert owns the movement. A losing one finds the existing (committed) row and compares it to the incoming request: identical body returns the original result; a different body returns `409`. Storing the idempotency claim separately from the movement would let a crash between the two commits leave a claimed key with no movement, or a movement with no claim — breaking exactly-once semantics. Sharing one transaction means concurrent identical requests produce exactly one balance movement; every other caller only reads.

## 5. Consistency vs Availability

Correctness is prioritized over availability. There is one authoritative Postgres instance; if it's unreachable, a transfer fails outright rather than being accepted into a queue and applied later. An "available" write that isn't durably recorded is worse than an outage for a ledger — it can silently lose or duplicate money. No write-behind cache or eventual-consistency path exists.

## 6. Observability

Logs are structured JSON (Logback + `logstash-logback-encoder`) with a correlation ID per request (`CorrelationIdFilter`, `X-Correlation-Id` header + MDC). Domain events are logged explicitly where they occur: `wallet.created`, `wallet.existing`, `transfer.completed`, `transfer.declined`, `transfer.idempotent_replay`, each with structured fields (ids, amounts, idempotency key, status). The same events increment Micrometer counters (`wallets.create`, `wallets.existing`, `transfers.completed`, `transfers.declined`, `transfers.idempotent_replay`), exposed alongside HTTP request-rate/latency/error metrics — with histogram buckets enabled for `http.server.requests` so p99 is computable via `histogram_quantile` — at `/metrics` in Prometheus format. `/health` reports application and database status.

## 7. AI Usage

### Directed by me
PostgreSQL as the sole authoritative state; transactional debit+credit as one atomic unit; the locking strategy (`SELECT ... FOR UPDATE`, both wallets, deterministic ascending-id order); database-enforced uniqueness for wallet creation and idempotency; the consistency-over-availability stance.

### AI-assisted implementation decisions
The Java/Spring Boot/JDBC implementation of the above; SQL statement details; validation and exception handling; Testcontainers-based integration and concurrency tests; the logging/metrics implementation; Docker/Compose/Railway deployment configuration; debugging (including diagnosing and fixing the FK-lock-ordering deadlock in Section 2); and this document. AI worked out implementation details, wrote and ran the tests, and found/fixed a real concurrency bug in the process — not merely typing a fully predetermined design.

## 8. Cost

Free-tier hosting throughout (containerized app + managed Postgres). Expected cost for this exercise is **₹0**, assuming free-tier limits are not exceeded.
