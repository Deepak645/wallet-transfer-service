# Wallet & P2P Transfer Service

Core concurrency/idempotency logic implemented — see [Current implementation status](#current-implementation-status).
Stack: Java 21, Spring Boot 3.3, PostgreSQL, Maven.

## Live Deployment

Deployed on Railway from this repository's Dockerfile, backed by a Railway-managed PostgreSQL instance.

- **Live API URL:** https://wallet-transfer-service-production-0e3e.up.railway.app
- **Health:** https://wallet-transfer-service-production-0e3e.up.railway.app/health
- **Metrics:** https://wallet-transfer-service-production-0e3e.up.railway.app/metrics

See "Deploying to Railway" below for the exact setup, and "Burst script" for how to run the concurrency scenarios against this URL.

## Run locally

Requires a local PostgreSQL instance (or run just the `db` service from
docker-compose — see below).

```bash
mvn spring-boot:run
```

By default it connects to `jdbc:postgresql://localhost:5432/wallet` with
user/password `wallet`/`wallet`. Override via env vars:
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.
The HTTP port defaults to `8080` and is also overridable via `PORT` (this
is what lets the same jar run on Railway, which assigns its own port).

Schema migrations (Flyway) run automatically on startup.

> Note: if you already have a native PostgreSQL install listening on port
> 5432 on this machine, `localhost:5432` is ambiguous between it and any
> docker-compose Postgres also publishing that port — point
> `SPRING_DATASOURCE_URL` at whichever one you mean, or stop the other.

## Run with Docker Compose

```bash
docker compose up --build
```

Brings up Postgres + the app, migrated and ready, on `http://localhost:8080`.
Credentials come from `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD`
(default `wallet`/`wallet`/`wallet` if unset) — copy `.env.example` to `.env`
and edit it if you want different local credentials; `.env` is gitignored.

## Deploying to Railway

The app is deployed on Railway (Dockerfile-based deploy; port and
datasource config are read from environment variables — see below). The
steps below describe the actual deployment setup, kept here so it can be
reproduced or audited.

1. Push this repository to a **public** GitHub repo (required by the
   exercise for the "public repo" deliverable, and simplest for Railway).
2. In the Railway dashboard: **New Project → Deploy from GitHub repo**,
   select this repo. Railway detects the root `Dockerfile` automatically
   and builds/deploys it as a service.
3. In the same Railway project: **New → Database → Add PostgreSQL** to
   provision a managed Postgres plugin/service (this is Railway's free
   managed Postgres).
4. On the **app service** (not the Postgres one), open **Variables** and
   add:
   - `SPRING_DATASOURCE_URL` = `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}`
   - `SPRING_DATASOURCE_USERNAME` = `${{Postgres.PGUSER}}`
   - `SPRING_DATASOURCE_PASSWORD` = `${{Postgres.PGPASSWORD}}`

   (`${{Postgres.VARNAME}}` is Railway's variable-reference syntax — adjust
   `Postgres` to whatever name Railway gave your Postgres service if it
   differs.) Do **not** set `PORT` yourself — Railway injects it, and the
   app already reads it (`server.port: ${PORT:8080}` in `application.yml`).
5. Trigger a deploy (pushing to the connected branch does this
   automatically; or use Railway's "Deploy" button). Watch the build logs
   for `Successfully applied 2 migrations` (Flyway) and `Started
   WalletServiceApplication` to confirm a clean startup.
6. Under the app service's **Settings → Networking**, click **Generate
   Domain** to get a public HTTPS URL.
7. Verify: `curl https://<your-domain>/health` should return
   `{"status":"UP",...}`, and `https://<your-domain>/metrics` should return
   Prometheus-format text.
8. Optional but recommended: under **Settings → Deploy**, set
   **Healthcheck Path** to `/health` so Railway uses the app's real health
   endpoint (rather than just container-started) to gate deploys/restarts.
9. Logs: Railway's **Deployments → View Logs** panel shows the live
   structured JSON logs for the deployed service. This dashboard view is
   not a public URL a reviewer can open directly — the submission instead
   includes a screen recording of these logs streaming during a burst-test
   run against the live URL above.

Railway environment variables needed (app service only — the Postgres
service configures itself):

| Variable | Value |
|----------|-------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` |
| `SPRING_DATASOURCE_USERNAME` | `${{Postgres.PGUSER}}` |
| `SPRING_DATASOURCE_PASSWORD` | `${{Postgres.PGPASSWORD}}` |

`PORT` is set automatically by Railway — do not set it manually.

## API

All endpoints except `/health` and `/metrics` require `Authorization: Bearer <token>`
(the token value is treated directly as the caller's user id — no token
registry exists yet).

| Method | Path              | Status |
|--------|-------------------|--------|
| POST   | `/wallets`        | Implemented — race-free get-or-create |
| GET    | `/wallets/{id}`   | Implemented (read-only) |
| POST   | `/transfers`      | Implemented — locking, idempotency, no-overdraft |
| GET    | `/transfers/{id}` | Implemented (read-only) |
| GET    | `/health`         | Implemented (Spring Boot Actuator) |
| GET    | `/metrics`        | Implemented (Prometheus exposition format) |

`POST /wallets` and `POST /transfers` both return `200` for every
successfully-processed request (a fresh transfer and an idempotent replay
of an existing one look the same at the HTTP layer; only the resource's
own `status` field differs). A same-key/different-body replay is `409`,
input validation failures are `400`, unknown wallets are `404`.

## Current implementation status

**Implemented** (see `AI-USAGE.md` for the distinction between
user-directed architecture and AI-assisted implementation).

- **Race-free get-or-create** (`WalletServiceImpl`): `INSERT ... ON CONFLICT
  (user_id) DO NOTHING`, falling back to a `SELECT` on conflict. Backed by
  a `UNIQUE` constraint on `wallets.user_id`.
- **Transfer concurrency control** (`TransferServiceImpl`): a single
  transaction that locks both wallet rows with `SELECT ... FOR UPDATE` in
  ascending wallet-id order (never in `from`/`to` order), checks
  `source.balance >= amount` only once both locks are held, and either
  declines cleanly or debits+credits — all before committing. See the
  class-level Javadoc on `TransferServiceImpl.createTransfer` for why the
  lock-then-claim-idempotency-key ordering specifically matters (a real
  deadlock was found and fixed here during testing — details below).
- **Idempotency**: `UNIQUE` constraint on `transfers.idempotency_key`,
  claimed via `INSERT ... ON CONFLICT DO NOTHING` in the *same transaction*
  as the wallet locks/movement. Same key + same body returns the original
  transfer; same key + different body is `409`; concurrent same-key
  requests serialize through the unique index so exactly one ever mutates
  balances.
- **No-overdraft**: `CHECK (balance_paise >= 0)` on `wallets`, plus the
  application-level balance check before debiting.

**A real bug found and fixed during testing:** `transfers.from_wallet_id`
and `to_wallet_id` are foreign keys into `wallets`. Postgres enforces FK
referential integrity by taking an implicit `FOR KEY SHARE` lock on the
referenced parent row at `INSERT` time — in column order (`from`, `to`),
not ascending id order. With the idempotency-claim `INSERT` running
*before* the explicit `SELECT ... FOR UPDATE` calls, concurrent `A->B` and
`B->A` transfers deadlocked through that implicit FK lock, even though the
explicit locks themselves were correctly ordered. Fixed by locking both
wallets explicitly (ascending order) *before* the idempotency-claim insert,
so the transaction already holds the stronger lock by the time the FK
check runs. Reproduced live with a concurrency integration test before the
fix (deadlock most runs at just 3-per-direction concurrency), confirmed
fixed after (stable across dozens of runs at higher concurrency).

**Still open / not implemented:**
- A funding/deposit path — there is no API to put money into a wallet
  (out of scope per the exercise; tests, the burst script, and manual
  verification all fund wallets by writing directly to the `wallets`
  table — see `scripts/burst-test.sh`'s `fund_wallet`/`run_psql`).

## Burst script

Local:

```bash
BASE_URL="${BASE_URL:-http://localhost:8080}" ./scripts/burst-test.sh
```

Against the deployed Railway instance:

```bash
BASE_URL=https://wallet-transfer-service-production-0e3e.up.railway.app ./scripts/burst-test.sh
```

Runs the three live-fire scenarios from the exercise against a running
instance and prints `PASS`/`FAIL` per assertion, with a non-zero exit code
if anything fails.

Scenarios 2 and 3 need wallets with a nonzero starting balance to have
money to move, but the exercise doesn't define a deposit/funding API and
wallets are always created at balance 0. The script establishes that
starting balance with a direct SQL `UPDATE` via `psql` — **this is test
setup only**, not an endpoint and not itself under test; the only API
exercised and asserted on is `POST /transfers` (and `POST /wallets` in
scenario 1). This DB write runs against whatever Postgres the person
running the script already has direct access to (a real `psql` client on
`PATH`, or `docker compose exec db psql` for a local stack) — it does not
require, and does not assume, access to the deployed Railway database's
credentials.

## Tests

`mvn test` runs:
- `TransferStatusTest` — trivial, no DB.
- `WalletServiceIntegrationTest` / `TransferServiceIntegrationTest` — real
  Postgres via Testcontainers, covering sequential and concurrent
  get-or-create, sufficient/insufficient balance, self-transfer and
  unknown-wallet rejection, same-key replay, same-key-different-body
  conflict, concurrent same-key idempotency, and concurrent opposite-direction
  transfers (conservation + no deadlock).

Testcontainers requires Docker to be running. On this environment,
Testcontainers 1.20.x failed to talk to a very recent Docker Desktop
release (docker-java API version negotiation bug); pinned to 1.21.4 in
`pom.xml`, which resolved it.

