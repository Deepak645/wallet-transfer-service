# Wallet & P2P Transfer Service

A backend service implementing wallets and peer-to-peer transfers with
transactional correctness, idempotency, and concurrency safety.

## Tech Stack

- Java 21
- Spring Boot 3.3
- PostgreSQL
- Maven
- Flyway
- Docker

## Key Guarantees

The service is designed around the following invariants:

- **No overdraft** — wallet balances never become negative.
- **Conservation** — a transfer debits one wallet and credits the other
  atomically.
- **Idempotency** — retrying the same transfer request does not move money
  more than once.
- **Race-free wallet creation** — concurrent requests for the same user
  result in a single wallet.
- **Deadlock avoidance** — wallet rows are locked in deterministic ascending
  ID order.
- **Integer money representation** — amounts are represented as integer
  paise; floating-point values are not used for money.

## Architecture

The application follows a controller → service → repository structure.

PostgreSQL is the authoritative source of truth for wallet balances and
transfer state.

Transfers use a single database transaction:

1. Lock both wallet rows using `SELECT ... FOR UPDATE`.
2. Acquire locks in ascending wallet-ID order.
3. Check the source balance.
4. Debit the source wallet and credit the destination wallet.
5. Persist the transfer and idempotency information in the same transaction.
6. Commit only when the complete operation succeeds.

Database constraints provide additional protection for wallet ownership,
idempotency keys, and non-negative balances.

See [`DESIGN.md`](DESIGN.md) for the detailed design rationale and concurrency
analysis.

## Live Deployment

The service is deployed on Railway with PostgreSQL.

- **API:** https://wallet-transfer-service-production-0e3e.up.railway.app
- **Health:** https://wallet-transfer-service-production-0e3e.up.railway.app/health
- **Metrics:** https://wallet-transfer-service-production-0e3e.up.railway.app/metrics

## API

All wallet and transfer APIs require:

```text
Authorization: Bearer <user-id>
```

The bearer token is treated as the caller's user ID for this exercise.

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/wallets` | Get or create a wallet for the authenticated user |
| `GET` | `/wallets/{id}` | Get wallet balance |
| `POST` | `/transfers` | Create a P2P transfer |
| `GET` | `/transfers/{id}` | Get transfer status |
| `GET` | `/health` | Application and database health |
| `GET` | `/metrics` | Prometheus-compatible metrics |

### Create Transfer

```http
POST /transfers
Authorization: Bearer <user-id>
Content-Type: application/json
```

```json
{
  "from": 1,
  "to": 2,
  "amount_paise": 2500,
  "idempotency_key": "unique-request-key"
}
```

Money amounts are integer paise.

A repeated request with the same idempotency key and the same request body
returns the original transfer. Reusing an idempotency key with a different
request returns `409`.

## Run Locally

Requires PostgreSQL.

```bash
mvn spring-boot:run
```

The default local database configuration is:

```text
jdbc:postgresql://localhost:5432/wallet
username: wallet
password: wallet
```

Override the datasource using:

```text
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
```

Flyway migrations run automatically when the application starts.

## Run with Docker Compose

```bash
docker compose up --build
```

This starts PostgreSQL and the application.

The application is available at:

```text
http://localhost:8080
```

## Concurrency / Burst Test

The repository includes:

```text
scripts/burst-test.sh
```

The script exercises the service through HTTP and verifies the main
concurrency guarantees.

### Scenario 1 — Concurrent Get-or-Create

Concurrent requests for the same user must produce exactly one wallet.

```bash
BASE_URL=https://wallet-transfer-service-production-0e3e.up.railway.app   ./scripts/burst-test.sh
```

### Full Burst Test

Scenarios involving transfers require the protected test-only funding
mechanism.

The endpoint is:

```text
POST /test/wallets/{id}/fund
```

It is disabled unless explicitly enabled on the deployment and protected by
a separate bearer token.

Run the full burst test by supplying the token through the environment:

```bash
BASE_URL=https://wallet-transfer-service-production-0e3e.up.railway.app TEST_FUNDING_TOKEN=<test-token>   ./scripts/burst-test.sh
```

The token is never stored in the repository or printed by the script.

The burst test covers:

- concurrent wallet creation
- concurrent identical idempotent transfers
- opposite-direction concurrent transfers
- conservation of total balance
- no negative wallet balances

## Observability

The application provides:

- structured JSON logs
- correlation IDs
- wallet and transfer domain events
- Prometheus-compatible metrics
- health information

Relevant domain events include:

```text
wallet.created
wallet.existing
wallet.test_funded
transfer.created
transfer.debited
transfer.credited
transfer.completed
transfer.declined
transfer.idempotent_replay
```

Metrics are available through `/metrics`.

## Tests

Run the complete test suite with:

```bash
mvn test
```

The test suite includes:

- wallet get-or-create concurrency tests
- transfer transaction and concurrency tests
- idempotency tests
- insufficient-balance tests
- conflicting idempotency-key tests
- opposite-direction transfer tests
- observability tests
- test-funding endpoint tests

Integration tests use PostgreSQL through Testcontainers.

## Test-Only Funding

`POST /test/wallets/{id}/fund` exists only to make the assignment's live
concurrency scenarios reproducible through the HTTP API.

It is not a production deposit feature.

The endpoint is:

- conditionally enabled
- protected by a separate bearer token
- disabled by default
- excluded from normal wallet/transfer authentication

No production funding or deposit functionality is implemented because it is
outside the scope of the exercise.

## Documentation

- [`DESIGN.md`](DESIGN.md) — system design, transaction strategy,
  concurrency, idempotency, consistency, and observability.
- [`AI-USAGE.md`](AI-USAGE.md) — disclosure of AI-assisted implementation,
  testing, debugging, and deployment work.

## Cost

**Total exercise cost: ₹0**

The deployed exercise uses free-tier infrastructure.
