# AI Usage Log

This file documents how AI was used during the implementation of this exercise.

The system design, architecture, concurrency strategy, idempotency approach,
database consistency mechanisms, and other load-bearing engineering decisions
were decided by the project owner.

AI was primarily used as an implementation assistant: generating boilerplate,
writing code based on the chosen approach, creating tests, debugging
implementation issues, and preparing configuration/documentation.

---

## Project Foundation / Boilerplate

**[AI-ASSISTED]**

The project foundation and boilerplate were generated with AI based on the
implementation requirements specified by the project owner.

The project owner specified:

- Java
- Spring Boot
- PostgreSQL
- Maven
- controller / service / repository / domain structure

AI generated the initial project structure, configuration, interfaces,
DTOs, exception handling, and supporting boilerplate.

---

## Core Design and Implementation

**[USER-DIRECTED]**

The project owner made the load-bearing design decisions for the wallet and
transfer system, including:

- PostgreSQL as the source of truth.
- Transfers performed within a single database transaction.
- Both wallet rows locked using `SELECT ... FOR UPDATE`.
- Deterministic ascending wallet-id lock ordering to avoid deadlocks.
- No use of `SKIP LOCKED`.
- Balance checked only after the required wallet locks are acquired.
- No-overdraft invariant.
- Database-enforced unique wallet ownership per user.
- Database-enforced idempotency-key uniqueness.
- Idempotency record and wallet movement handled within the same transaction.
- Same-key/same-request returns the original result.
- Same-key/different-request returns `409`.
- Race-free get-or-create behavior.
- Consistency/correctness prioritized over availability for monetary operations.

AI implemented the above design after the decisions were specified.

---

## Testing

**[AI-ASSISTED]**

AI was used to implement automated tests and the live burst-test script
based on the required scenarios and acceptance criteria.

The tests cover:

- concurrent wallet creation
- concurrent identical idempotent transfers
- opposite-direction concurrent transfers
- conservation of total balance
- no negative balances
- idempotency replay
- conflicting idempotency keys

The test scenarios and correctness requirements were specified by the
project owner.

---

## Railway Deployment Preparation

**[AI-ASSISTED]**

AI was used to prepare the application configuration for deployment to
Railway, including environment-variable based configuration, Docker
configuration, `.env.example`, and deployment documentation.

The deployment itself is being performed manually by the project owner.

AI did not create the GitHub repository, push the code, or deploy the
application to Railway.

---

## Summary

AI was used primarily as an implementation and productivity tool.

The project owner retained responsibility for the system design and
load-bearing engineering decisions, particularly those affecting:

- correctness
- concurrency
- transactions
- consistency
- idempotency
- database constraints
- overdraft prevention
- deadlock avoidance

AI-generated implementation was reviewed and tested against the required
behavior.