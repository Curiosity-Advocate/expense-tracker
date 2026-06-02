## ADR-0019 — Bank integration: CSV import in v2.0, aggregators deferred to v3.0

> **Context:** Covers v2.0 item B1 (bank integration). Driven by: N6, N7. Related to [ADR-0020](0020-csv-import-architecture.md) (which captures the CSV-side architecture in detail), [ADR-0011](0011-three-layer-rls-defence.md) (data isolation).
>
> **Filename note:** the slug `basiq-credential-model` reflects the earlier direction; the file was rewritten in place rather than renamed to preserve the ADR number and inbound links.

**Status:** Accepted. Adopted in v2.0 (B1, finalised 2026-06-01).

---

## Context

B1 started life as a Basiq CDR integration with Bitwarden-managed per-user credentials. Two discoveries during briefing forced a re-spec:

**1. Aggregator costs.** Every Australian open-banking aggregator wraps the same CDR data with similar pricing structures. None publish full pricing on their websites; ranges below are public-tier estimates and discoverable signup info:

| Provider | Headline cost | Minimum commitment | Pricing page |
|---|---|---|---|
| Basiq | $0.50/user/mo + undisclosed platform fee | 12 months | [basiq.io/pricing](https://www.basiq.io/pricing.html) |
| SISS Data Services (ACSISS) | $25/mo CDR-only or $49/mo CDR+direct; $399 setup | Not disclosed (NFP discount available) | [acsiss.com.au](https://acsiss.com.au/) |
| illion BankStatements | $9.90–$29.90/mo + **$3.85 per submission** + $99 setup | No lock-in | [bankstatements.com.au/pricing](https://bankstatements.com.au/pricing) |
| Frollo / Adatree / Fiskil | Sales-only; positioned as enterprise-tier | Not disclosed | various |
| Envestnet Yodlee | Sales-only; estimates "low thousands to mid five-figures" setup | Annual minimums | [yodlee.com pricing](https://www.yodlee.com/au/company/pricing) |

For a personal-use expense tracker at our scale (10 users, weekly imports), **the cheapest legitimate CDR path is SISS at ~$700 first year, ~$300/yr ongoing**. The next cheapest (Basiq sandbox) is free but uses synthetic test banks — not connectable to real Australian bank accounts.

**2. A free path exists.** All major Australian banks already let users export transactions as CSV (CommBank up to 15 months, Westpac ~18 months, etc.) for free. A CSV-upload flow gives the same end result for the user with manual export overhead, zero ongoing cost, and zero compliance machinery — at the price of a monthly manual step.

## Decision

**v2.0 ships CSV import only.** Aggregator integration deferred to v3.0 when cost is justified by demand (e.g. the project monetises, or non-technical users join who can't do manual exports).

The bank-integration module is **designed for swappable sources from day one** — see ADR-0020. v3.0 adds an aggregator implementation under the same module port; no existing v2.0 code changes. The pivot from aggregator-first to CSV-first cost ~half a day of redesign and ~80% of the v2.0 code was preserved (the `raw_bank_transactions` hash chain, `dead_letters` table, async processor pattern, ArchUnit module seal — all unchanged).

### What v2.0 ships

- Per-account `csv_import_connections` (V28); one row per `bank_account` that has CSV import set up
- Six per-bank parsers behind one `CsvBankParser` port: CBA, ANZ, Ubank, AMP, Qudos, Suncorp
- Date-dispatched parser version selection — bank format revisions don't require user-side config changes
- Async upload + status polling: `POST /api/v1/bank-accounts/{id}/csv-import` returns 202, processing runs on a dedicated `csv-import-*` thread pool, `GET /api/v1/bank-data/csv-imports/{id}` polls until COMPLETED
- Startup recovery for `csv_imports` rows stranded by JVM restart
- Per-account rate limit: at most one successful import (with `imported_count > 0`) per 7 days, and only one RUNNING at a time
- Module seal via ArchUnit (`com.finance.bankintegration..` is internally cohesive)

### What v3.0 may add

Aggregator integration as a sibling implementation under the same module:

```
com.finance.bankintegration/
├── parser/        (CSV — v2.0)
├── service/       (CsvImportService, async processor — v2.0)
└── basiq/         (or siss/, fiskil/ — v3.0)
```

With:
- `basiq_import_connections` table alongside `csv_import_connections` (per-account, holding Basiq User ID + consent ID)
- An "at most one active source per bank_account" cross-table constraint (deferred-FK or partial unique index)
- A switch-source endpoint to handle cutover with a cutover date so old data stays attributed to its original source
- `BASIQ_API_KEY` moved from `.env` to Bitwarden Secrets Manager (REST API; not the beta Java SDK) for resume signal + audit log + rotation support

## Consequences

**Positive:**

- Zero ongoing cost; the app is genuinely usable for free on day one
- No 12-month aggregator commitment; users can experiment without lock-in
- Code is small and contained — bank integration is ~1.5 kLOC across `core/bankintegration/`, `adapters/bankintegration/`, `api/bankintegration/`
- The architecture is designed for swappable sources; v3.0 adds a second source alongside CSV without touching the first
- Module seal (ArchUnit) means deleting CSV entirely in some hypothetical future v4.0 is `rm -r com/finance/bankintegration/csv/parser/` plus a migration drop — touches nothing else
- DB schema (`raw_bank_transactions`, `dead_letters`, `csv_imports`) is source-agnostic; aggregator sources land in the same tables with different `source_format` values

**Negative:**

- Manual export step per import is friction the user has to accept (mitigated by `csv_export_url` bookmark on each connection)
- Six bank-specific parsers to maintain when banks revise their export formats; bumped to ~1 dev-day per bank-revision via the date-dispatched parser model
- The 7-day rate limit is product policy, not infrastructure; a malicious user could still upload junk CSVs once per week
- We have no aggregator-side reconciliation; if a user discovers their CSV missed transactions, we have no way to detect that

**Neutral:**

- The Bitwarden Secrets Manager work (deferred from earlier briefing) is now coupled to the aggregator work — it lands when there's a meaningful secret to manage. Until then, `.env`/Render-secrets are adequate for the only secret we hold (JWT signing key).
- v3.0 async-bank-sync (deferred from earlier briefing) is also coupled — when aggregator ships, its sync endpoint enqueues to a generic `jobs` table that B3's normalisation worker introduces.

## Alternatives considered

- **Basiq with sandbox-only deployment.** Free, but synthetic banks only — can't connect to real accounts. Resume signal exists but the project isn't personally usable.
- **SISS at $25/mo + $399 setup.** Cheapest real-CDR path. Rejected for v2.0 because the value proposition is unclear before there are real users to justify the cost.
- **Becoming a CDR Accredited Data Recipient directly.** $100k-$300k initial + ongoing FTE per ACCC estimates. Not realistic for a personal project.
- **CDR Representative model (sponsored by an accredited recipient).** Cheaper than full accreditation but requires a willing sponsor — sponsors generally serve businesses, not personal projects.
- **Screen scraping.** Banks actively block this post-CDR; violates ToS; not viable.

## Triggers for revisiting

- Project gains non-technical users who can't reliably do monthly CSV exports
- Project monetises or is otherwise able to justify a $25–$100/mo aggregator fee
- A bank changes their CSV export format in a way that breaks one of our parsers and the user fixes it by switching to an aggregator instead of waiting for a parser update
- The 7-day per-account rate limit becomes a meaningful UX problem (e.g. users want daily updates)
