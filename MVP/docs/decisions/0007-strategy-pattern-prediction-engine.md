# ADR-0007 — Strategy pattern for prediction engine with version pinning

> **Context:** Expands [overview.md §6](../overview.md#6-how-users-interact-with-the-system). Driven by: F31, F32, F33.

**Status:** Accepted. Adopted in v1.0.

---

## Context

The system predicts whether a spending target will be exceeded. v1.0 uses a naive daily-rate algorithm: `projected = (spent / daysElapsed) * totalDays`. Future versions will introduce smarter algorithms — weighted recent days, seasonality, anomaly-aware projection.

Two requirements pull in opposite directions:

1. **Historical reproducibility.** A user reading "we projected you'd exceed by $68.75 on May 12" should be able to reproduce that same prediction with the same inputs. Changing the algorithm in place breaks this.
2. **Easy evolution.** Adding a new algorithm should not require touching existing code.

## Decision

Adopt the **strategy pattern** with explicit version pinning:

- A `PredictionStrategy` interface defines `canHandle(PredictionContext)` and `predict(PredictionContext)`.
- Each algorithm is a separate class with a public `NAME` and `VERSION` constant. v1.0 ships `NaiveDailyRateStrategy` with `NAME = "NaiveDailyRate"`, `VERSION = "1.0"`.
- The `PredictionEngine` holds an ordered list of strategies. For a given context it calls `canHandle` on each in order and delegates `predict` to the first match.
- Every `PredictionResult` carries `strategyUsed` and `strategyVersion` so the response is self-describing.
- **The cardinal rule:** existing strategy classes are never modified or deleted. New algorithms become new classes with new names/versions.

## Consequences

**Positive.**
- Historical predictions are reproducible — run the same class with the same inputs, get the same result.
- New algorithms are additive — no existing code changes, no regression risk on past behaviour.
- Clients can record `strategyUsed`/`strategyVersion` alongside the prediction and reproduce it later.
- The default strategy with the loosest `canHandle` sits last in the list and guarantees the chain never falls through empty-handed.

**Negative.**
- The number of strategy classes grows monotonically. Old strategies that no algorithm targets any more still exist in the codebase. This is the intended trade.
- A user with an old saved prediction may not get the same algorithm on a fresh call (the engine picks the best current strategy that `canHandle`s). Reproducibility means "given the same algorithm class, same inputs, same output" — not "always the same algorithm".

## Alternatives considered

- **Single mutable strategy with versioned config.** Rejected — code paths are far easier to reason about than configuration trees. A bug in v1.0 logic should not be hot-fixable by changing config that also touches historical reproduction.
- **Modify the strategy and bump the version string.** Equivalent to the rule "never modify" but more fragile: a careless contributor might bump the version and edit logic in one commit, breaking historical reproduction unintentionally. The hard rule "new class, never modify" makes the boundary impossible to miss.
