# AI Usage Log

This file documents how AI was used during the implementation of this exercise.

The project owner directed the core system-design decisions and reviewed the
resulting implementation. AI was used extensively as an implementation,
debugging, testing, and documentation assistant.

The distinction below separates the load-bearing architectural decisions
directed by the project owner from implementation details where AI contributed
ideas or generated code that was subsequently reviewed and accepted.

---

## Project Foundation / Boilerplate

**[AI-ASSISTED]**

The project foundation and boilerplate were generated with AI based on the
implementation requirements and technology choices specified by the project
owner.

The project owner specified:

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

The project owner directed the primary load-bearing design decisions:

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

AI also contributed to implementation-level decisions and suggestions during
development, including SQL/JDBC details, validation, exception handling,
transaction implementation, and debugging. These details were reviewed by the
project owner rather than being presented as independently designed
architecture.

---

## Testing and Concurrency Verification

**[AI-ASSISTED / USER-DIRECTED]**

The project owner specified the correctness properties and concurrency
scenarios that needed to be verified.

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

The correctness requirements and acceptance criteria were directed by the
project owner.

---

## Observability

**[AI-ASSISTED / USER-DIRECTED]**

The project owner required the application to provide structured logs,
correlation IDs, domain events, metrics, and a health endpoint.

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

The project owner performed the GitHub repository setup, code push, Railway
deployment, database configuration, and live verification.

AI did not independently deploy the application or control the deployment
account.

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

The project owner retained responsibility for the primary system-design
decisions affecting:

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
reviewed and tested by the project owner against the required behavior.