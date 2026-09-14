# AI Usage Log

This file documents how AI was used during the implementation of this exercise.

I directed the core system-design decisions and reviewed the resulting
implementation. AI was used extensively as an implementation, debugging,
testing, deployment, and documentation assistant.

The distinction below separates the load-bearing architectural decisions
I directed from implementation details where AI contributed ideas or
generated code that I subsequently reviewed and accepted.

---

## Project Foundation / Boilerplate

**[AI-ASSISTED]**

The project foundation and boilerplate were generated with AI based on the
implementation requirements and technology choices I specified.

I specified:

- Java
- Spring Boot
- PostgreSQL
- Maven
- controller / service / repository / domain structure

AI generated the initial project structure, configuration, interfaces, DTOs,
exception handling, and supporting boilerplate.

---

## Core System Design

**[USER-DIRECTED]**

I directed the primary load-bearing design decisions:

- PostgreSQL as the authoritative source of truth.
- Transfers performed within a single database transaction.
- Both wallet rows locked using `SELECT ... FOR UPDATE`.
- Deterministic ascending wallet-ID lock ordering to avoid deadlocks.
- No use of `SKIP LOCKED`.
- Balance checked only after the required wallet locks are acquired.
- No-overdraft invariant.
- Database-enforced unique wallet ownership per user.
- Database-enforced idempotency-key uniqueness.
- Idempotency handling and wallet movement performed within the same transaction.
- Same-key/same-request returns the original result.
- Same-key/different-request returns `409`.
- Race-free get-or-create behavior.
- Consistency/correctness prioritized over availability for monetary operations.

AI was then used to translate these decisions into the application and
database implementation.

AI contributed suggestions at the implementation level, including SQL/JDBC
details, validation, exception handling, transaction implementation, and
debugging. I reviewed these suggestions and retained responsibility for the
resulting design decisions.

---

## Testing and Concurrency Verification

**[AI-ASSISTED / USER-DIRECTED]**

I specified the correctness properties and concurrency scenarios that needed
to be verified.

AI was used to implement automated tests and the live burst-test script.

The tests cover:

- concurrent wallet creation
- concurrent identical idempotent transfers
- opposite-direction concurrent transfers
- conservation of total balance
- no negative balances
- idempotency replay
- conflicting idempotency keys
- insufficient-balance transfers
- relevant API validation and error cases

AI also assisted with debugging test failures and implementation issues
discovered during verification.

The correctness requirements and acceptance criteria were directed by me.

---

## Observability

**[AI-ASSISTED / USER-DIRECTED]**

I required the application to provide structured logs, correlation IDs,
domain events, metrics, and a health endpoint.

AI assisted with implementing:

- structured JSON logging
- correlation IDs
- wallet and transfer domain events
- Prometheus-compatible metrics
- HTTP request metrics
- health endpoint configuration
- observability tests

The implementation was reviewed and verified against the required behavior.

---

## Docker and Railway Deployment

**[AI-ASSISTED]**

AI assisted with deployment preparation, including:

- multi-stage Docker configuration
- non-root container configuration
- healthcheck configuration
- environment-variable based database configuration
- `.env.example`
- Railway deployment configuration
- deployment documentation

I performed the GitHub repository setup, code push, Railway deployment,
database configuration, and live verification.

AI did not independently deploy the application or control the deployment
account.

---

## Test-Only Funding Endpoint and Burst Script Update

**[AI-ASSISTED / USER-DIRECTED]**

I directed the requirement and constraints for a test-only wallet funding
mechanism: a protected endpoint gated by an enabled flag and a bearer token,
built on the existing repository/service architecture, never bypassing
existing database constraints, and never becoming a production deposit
feature.

I also directed replacing the burst script's earlier pre-funded-wallet
fixture mechanism with calls to this endpoint, so the script could exercise
a live deployment over HTTP only, with no database access of any kind.

AI implemented:

- `POST /test/wallets/{id}/fund`, conditionally registered only when
  `test.funding.enabled=true`, so the endpoint does not exist and returns
  `404` when disabled, and guarded by its own bearer-token filter when enabled
- the funding logic, reusing the existing wallet row lock
  (`SELECT ... FOR UPDATE`) rather than introducing a new locking strategy
- structured logging (`wallet.test_funded`) and a Micrometer counter
  (`wallets.test_funded`) for the new endpoint, following the same
  after-commit pattern already used for transfer events
- integration tests for valid funding, unknown wallet, zero/negative amount,
  missing/wrong token, and the disabled-endpoint case
- the burst script rewrite to use the new endpoint instead of database
  fixtures
- a fix to an existing exception handler that was turning unmapped-path
  requests into `500` instead of `404`, discovered while verifying the
  disabled-endpoint behavior

I reviewed this implementation, ran the full test suite, and verified the
endpoint's enabled/disabled/token behavior before accepting it.

---

## Debugging and Iteration

**[AI-ASSISTED]**

AI was used during development to investigate implementation and test issues,
including:

- API request/DTO mismatches
- concurrency behavior
- deadlock scenarios
- database constraint behavior
- observability configuration
- metrics naming/export behavior
- deployment configuration issues

Fixes were reviewed and verified through automated tests and live/manual
testing.

---

## Summary

AI was used as an implementation and engineering productivity tool throughout
the exercise.

I retained responsibility for the primary system-design decisions affecting:

- correctness
- concurrency
- transactions
- consistency
- idempotency
- database constraints
- overdraft prevention
- deadlock avoidance

AI contributed substantially to implementation details, testing, debugging,
deployment preparation, and documentation. The resulting implementation was
reviewed and tested by me against the required behavior.