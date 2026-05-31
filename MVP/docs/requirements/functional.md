# Functional Requirements

> **Context:** Expands [overview.md §2](../overview.md#2-how-the-problem-was-carved-up). The IDs below (F1–F37) are cited throughout [architecture/data-model.md](../architecture/data-model.md), [architecture/api-contract.md](../architecture/api-contract.md), and the [decisions/](../decisions/) folder.

Each requirement is a single capability statement. Grouped by business category from the overview. IDs are stable and used as the system's connective tissue — every table, endpoint, and decision references them.

---

## Authentication and identity

| ID | Requirement |
|---|---|
| F1 | User can register with username, email, and password |
| F2 | User can login with username and password and receive a JWT |
| F3 | User can logout — JWT must be invalidatable before expiry |
| F4 | Failed login attempts tracked and account locked after 5 failures within 10 minutes for 15 minutes |
| F5 | User can view their own profile |
| F6 | User can update their discoverability flag |

---

## Delegation

| ID | Requirement |
|---|---|
| F7 | User can grant temporary access to another user for 1 to 30 days |
| F8 | Grantee must be opted in via `isDiscoverable` to be granted access |
| F9 | User can revoke a grant early |
| F10 | User can list their grants |
| F11 | Delegation requires step-up authentication via sudo token per request |
| F12 | Delegation scope limited to expense endpoints only |
| F13 | Expired grants are enforced at request time by the gateway filter |

> **Note:** F7–F13 shipped in v2.0 (D1 grants + D2 sudo tokens + D3 gateway filter). See [../roadmap.md](../roadmap.md).

---

## Categories

| ID | Requirement |
|---|---|
| F14 | System defines a set of default categories visible to all users |
| F15 | User can create private categories |
| F16 | User can update their own category name and description |
| F17 | Categories can be self-referential — a category can have a parent |
| F18 | Categories cannot be deleted in version 1.0 |
| F19 | Every expense has at least one category — `UNCATEGORISED` if none specified |

---

## Expenses

| ID | Requirement |
|---|---|
| F20 | User can create a manual expense with amount, merchant, date, categories, payment method, bank account, notes |
| F21 | User can edit a manual expense |
| F22 | User can soft delete a manual expense |
| F23 | User can view a single expense |
| F24 | User can list expenses filtered by date range, merchant, categories, payment method, bank account, amount range, source |
| F25 | User can get aggregated summary grouped by category, merchant, or month |
| F26 | Category weights computed at write time as even split across categories |
| F27 | Idempotency key prevents duplicate expenses from client retries |

---

## Targets and predictions

| ID | Requirement |
|---|---|
| F28 | User can create a single-category, multi-category, or total spending target |
| F29 | Multi-category targets support inclusive and exclusive participation |
| F30 | User can list and delete their targets |
| F31 | User can query target status with current spend and end-of-period projection |
| F32 | Predictions computed on demand using a strategy chain |
| F33 | Confidence level derived from percentage of days remaining |

---

## System operations

| ID | Requirement |
|---|---|
| F34 | Partition created annually for expense data |
| F35 | Old partitions archived annually for cold storage |
| F36 | Expired tokens, idempotency keys, and access grants cleaned up nightly |
| F37 | Materialised views refreshed on write in version 1.0 |
