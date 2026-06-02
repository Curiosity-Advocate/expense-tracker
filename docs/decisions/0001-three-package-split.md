# ADR-0001 — Four-module split (core / adapters / api / worker)

> **Context:** Expands [overview.md §3](../overview.md#3-architectural-implications). Detail in [architecture/module-boundaries.md](../architecture/module-boundaries.md). Driven by: N1.

**Status:** Accepted. Adopted in v1.0 with a three-module shape (`core`, `api`, `worker`). The `adapters` module was carved out of `api` in v1.1, finishing the split this ADR originally promised.

---

## Context

The system has three distinct kinds of responsibility: durable business logic, HTTP request handling, and background job processing. Each has a different rate of change, a different operational profile, and different testing needs.

- Business logic changes when new domains are added (bank integration, AI categorisation, mortgage modelling). It is what every future feature builds on.
- The HTTP layer changes whenever the interface evolves — a new API version, a mobile client, a different request shape.
- The worker changes when new background tasks are added. It runs on a different schedule, has different resource needs, and may eventually need independent scaling.

If all three live in one package, every change touches code unrelated to the change. Tests are slow because they pull in HTTP and JDBC machinery even for pure logic. The business core cannot be reused outside an HTTP context without major rework.

## Decision

Split the codebase into four Gradle modules with strict, compile-enforced dependency directions:

```
core/       no dependencies on adapters, api, or worker
adapters/   depends on core
api/        depends on adapters (and transitively on core)
worker/     depends on core only (raw-JDBC against PostgreSQL; no JPA)
```

The `core` module contains domain types (commands, queries, value objects), port interfaces, domain exceptions, and pure-algorithmic strategies like `NaiveDailyRateStrategy`. It is forbidden from importing Spring annotations, JDBC, or any HTTP/web library — a rule enforced by ArchUnit tests in addition to the Gradle classpath.

The `adapters` module holds infrastructure implementations: JPA entities, Spring Data repositories, service-layer implementations, and Flyway SQL migrations. Anything that could plausibly be swapped (database engine, ORM) sits here.

The `api` module is the runnable Spring Boot HTTP app: controllers, DTOs, security, config, the `GlobalExceptionHandler`, and `ApiApplication.java`. Spring Boot, Spring MVC, and Spring Security are taken as givens — they are not abstracted behind ports because no realistic swap is planned.

The `worker` module is a separate Spring Boot process for scheduled jobs. It deliberately does not depend on `adapters` — its housekeeping queries are simple raw SQL and pulling in the JPA classpath would be dead weight.

## Consequences

**Positive.**
- Business logic is independently testable with plain Java — no Spring context, no embedded DB.
- New deployment targets (a CLI, a different framework) reuse `core` unchanged.
- Boundary violations fail the Gradle build, not a code review.
- The Worker shares Core with the API — same services, same ports, same correctness guarantees.

**Negative.**
- Four modules add Gradle complexity over a single source set.
- Every new domain type touches at least two modules (`core` for the interface, an adapter for the implementation).
- Some developers will resist the indirection until they hit the first refactor that the boundary prevents.

## Alternatives considered

- **Single Gradle module with package-level discipline.** Rejected because package boundaries are not enforced by the compiler — a stray import bypasses them silently. The cost of detecting the drift later is much higher than the cost of multiple modules now.
- **API and Worker in one process with profiles.** Rejected because the operational profiles diverge: the API is request-driven and stateless, the Worker is scheduled and may need to run with elevated DB credentials. Coupling them creates a process that is hard to size and reason about.
