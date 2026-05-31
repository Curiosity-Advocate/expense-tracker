## ADR-0019 — Basiq credential model: `.env` now, Bitwarden Secrets Manager later

> **Context:** Covers v2.0 item B1 (Basiq CDR integration) — specifically the choice of how to store and access the Basiq API key and per-user enrolment identifiers. Driven by: N6, N7. Related to [ADR-0011](0011-three-layer-rls-defence.md) (data isolation) and the v3.0 secret-management entry in [roadmap.md](../roadmap.md).

**Status:** Accepted. Adoption in progress (B1, started 2026-05-31). Will be marked Adopted at the end of B1.6 when the implementation and doc updates land together.

---

## Context

B1's original spec called for a "Bitwarden CLI/SDK integration for OAuth credential lookup" — the assumption being that we'd store *per-user bank credentials* in Bitwarden and read them at sync time. While briefing the implementation, two things forced a re-spec:

**1. The Basiq model doesn't require per-user secrets.**

Basiq, like Plaid (US) and TrueLayer (UK), is an open-banking aggregator. Their model has three distinct credentials, only one of which is sensitive:

| Credential | Owner | Sensitivity | Source |
|---|---|---|---|
| **App-level API key** | One per company/app, ever | High — the master secret | Issued by Basiq when registering as a developer |
| **Basiq User ID** | Per end-user | Low — opaque identifier | Created via `POST /users` using the app-level key |
| **Consent ID / access token** | Per end-user, per bank connection | Medium — grants read access to ONE user's bank data | Result of the user authenticating against their bank through Basiq's *hosted* consent UI |

End users never give us their bank login. They authenticate against their bank through Basiq's hosted UI (the same pattern as "Sign in with Google"). The aggregator holds the bank-side credentials; we only receive the consent ID afterwards. The Basiq User ID and consent ID are useless without the app-level API key.

So the right question is not "how do we store per-user secrets?" — it's **"how do we protect one app-level secret?"**

**2. The Bitwarden CLI's session-unlock dance is incompatible with unattended operation.**

The Bitwarden CLI we'd originally proposed (`bw get item …`) requires an unlocked session — derived from interactively entering the master password into `bw unlock --raw` and capturing the resulting `BW_SESSION` token. Three theoretical paths to automate this all collapse the security model:

- Storing the master password in env vars defeats the whole point of having a vault — it's a strictly *worse* secret than the Basiq key we'd be protecting.
- A Bitwarden API key authenticates the device but does not derive the encryption key — you still need the master password to decrypt.
- A Bitwarden service-account approach exists — but it's a different product (Secrets Manager), not the password manager CLI.

The Bitwarden CLI is built for human-to-human credential sharing. Service-to-service auth is a different problem.

## Decision

**Two parts, separated by time horizon:**

### v2.0 (now): `.env` / Render secret env vars

- **`BASIQ_API_KEY`** — single app-level secret, read by `BasiqProperties` (`@ConfigurationProperties`) from the standard Spring env-var lookup. Local dev sources it from `.env` (gitignored); deploys source it from Render's secret env-var mechanism (encrypted at rest in their infrastructure, injected into the container's environment, never visible in logs).
- **Per-user identifiers** — `basiq_user_id` and consent IDs live in a new `bank_connections` table (V28). These are *not* secrets; they're scoped identifiers whose value depends on having the app-level key. A DB leak alone reveals identifiers, not access.
- **No Bitwarden integration in v2.0.** The credential-store port and CLI implementation that B1's original spec proposed are removed from scope.

### v3.0 (deferred): Bitwarden Secrets Manager via REST API

Migrate the app-level key from `.env` / Render secrets to Bitwarden Secrets Manager:

- **Why Secrets Manager, not the password-manager CLI.** Bitwarden Secrets Manager is a separate product designed for service-to-service auth. Authentication is via a long-lived machine-account access token; no master-password unlock dance. Free tier (3 projects, 3 machine accounts, unlimited secrets) is enough for personal use forever.
- **Why REST, not the Java SDK.** The Bitwarden Java SDK is marked beta and bundles a JNI binding to a Rust native library. The native lib has to match the deployment platform (linux-x86 vs linux-arm vs macOS), which makes Pi / NAS / arm64-Render deploys finicky. Calling the REST API directly with Spring's `RestClient` avoids the JNI dependency and the SDK version-skew risk for ~50 lines of code.
- **What we lose.** Network call at startup to fetch the secret; app can't start if Bitwarden's API is unreachable. Acceptable for a personal app.
- **What we gain.** Audit log of secret access, rotation support, unified place to manage secrets across multiple deployments (laptop + Render + dev), resume signal of "I built a machine-account-based secrets-manager integration with bootstrap-secret hierarchy."

### What never happens

- **No KMS / HashiCorp Vault / cloud secrets manager** unless the user base grows past ~10 trusted users *and* the project moves to a cloud platform that natively provides one. KMS envelope encryption is the gold standard but is ceremony that doesn't pay off at our scale.
- **No storage of Basiq access tokens in our DB.** Basiq's access tokens (60-min lifetime, obtained from the app-level key + consent ID) are cached in memory only — never persisted. If the app restarts mid-sync, we re-mint.

## Consequences

**Positive:**

- Trivial setup; ships B1 without dragging a credential-store sub-system through it.
- Compromise of the DB alone reveals nothing about bank access — attacker needs the app-level Basiq key too. Same threat profile as Plaid clients running on Render/Heroku without KMS.
- Clear migration path documented; v3.0 work is bounded and adds resume value.

**Negative:**

- The Basiq API key is exposed to anyone with read access to the Render dashboard or local `.env` file. Mitigation: rotate the key from Basiq's UI if compromise is suspected; monitor Basiq's dashboard for unexpected API call patterns.
- A leak via a misclicked `git add` is possible. Mitigation: `.env` is gitignored from day one, and pre-commit hooks (future) can scan for secret-shaped strings.

**Neutral but worth noting:**

- The B1 sync endpoint is synchronous (fetch + persist + dead-letter inline). Async via a job queue is deferred to v3.0 once B3's normalisation worker creates the `jobs` table. Cost of the eventual refactor: ~2–4 hours, mostly DTO changes and test rewrites. The choice was: ship sync now or ship a half-broken async-stub endpoint while waiting for B3. Sync won. See the v3.0 "Async bank-sync" entry in [roadmap.md](../roadmap.md).

## Alternatives considered

- **Bitwarden password-manager CLI with operator-unlocked session** — rejected. Manual unlock step on every prod restart is incompatible with Render auto-restarts and is operationally painful even for a personal app.
- **Bitwarden Java SDK (beta) for Secrets Manager** — rejected for v3.0 in favour of REST. Beta status + JNI native-lib dependency add maintenance tax for no functional benefit; the REST surface is small enough to call directly.
- **AWS KMS / Secrets Manager** — rejected for now. Adds a cloud account, IAM setup, ongoing cost (small but real), and doesn't fit a "runs on my laptop" personal-use deployment.
- **HashiCorp Vault self-hosted** — rejected. Running another stateful service alongside Postgres doubles the operational surface for one secret.
- **OS-native keychain (macOS Keychain / Linux Secret Service)** — rejected. Couples the deploy to a specific OS, doesn't survive containerised deploys, no audit log.
