# Expense Tracker — Documentation

**Start here:** [overview.md](overview.md) — the design narrative that walks from problem to solution end-to-end. Read it top to bottom and follow the deep links into the detail files below when you want more depth.

---

## Layout

```
docs/
├── overview.md                    The narrative spine — start here
├── requirements/                  What the system must do, and how well
├── architecture/                  Structural shape — diagrams, modules, data, API
├── decisions/                     Architecture Decision Records (ADRs)
├── operations/                    Running, deploying, and maintaining the system
├── roadmap.md                     MVP scope and what comes next
└── _legacy/                       Earlier design documents, archived for context
```

---

## Files

### Requirements
- [requirements/functional.md](requirements/functional.md) — F1–F37, what the system does
- [requirements/non-functional.md](requirements/non-functional.md) — N1–N22, qualities the system must hold

### Architecture
- [architecture/c4-diagrams.md](architecture/c4-diagrams.md) — C4 Level 1, 2, and 3 diagrams
- [architecture/module-boundaries.md](architecture/module-boundaries.md) — Core, adapters, API, worker — dependency rules and responsibilities
- [architecture/data-model.md](architecture/data-model.md) — All tables, materialised views, partition registry
- [architecture/api-contract.md](architecture/api-contract.md) — Every endpoint with sequence diagrams

### Decisions
- [decisions/](decisions/) — One file per non-obvious architectural choice. Numbered sequentially (ADR-0001, ADR-0002, …).

### Operations
- [operations/local-development.md](operations/local-development.md) — Running the system on your machine
- [operations/deployment.md](operations/deployment.md) — Docker, Render, GitHub Actions
- [operations/scheduled-jobs.md](operations/scheduled-jobs.md) — Cron schedule, idempotency, failure handling

### Roadmap
- [roadmap.md](roadmap.md) — MVP scope, v1.1, v2.0, v2.1 and beyond

---

## Conventions

**ID cross-references.** Functional requirements are numbered F1–F37, non-functional N1–N22, and decisions are ADR-NNNN. Every document cites the IDs it implements or is driven by, so a reader following any single ID can find every place it is discussed.

**Backreference blocks.** Every detail file opens with a `> Context:` block linking back to the section of `overview.md` it expands, the requirements it implements, and the ADRs it references.

**ADR format.** Each ADR uses a fixed five-section template (Status, Context, Decision, Consequences, Alternatives considered). The template itself was chosen in this documentation pass — MVP/README does not use ADRs and does not enumerate rejected alternatives for most decisions.
