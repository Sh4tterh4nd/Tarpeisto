# BigContainers Implementation Status

Status: Living record of what exists, as of 2026-09-27

Purpose: This document records **what is actually built and verified**, so that a contributor (human
or agent) can continue the work without rediscovering it.

- [`SPECIFICATION.md`](SPECIFICATION.md) defines required behavior.
- [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) defines intended delivery order.
- **This document records reality**, including where reality deliberately departs from the plan.

Read it together with [`DEVELOPMENT_POLICIES.md`](DEVELOPMENT_POLICIES.md) and the accepted ADRs.

## 1. Summary

| Phase   | Scope                                                                           | State                                                                                               |
| ------- | ------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| 0       | Repository, build, CI, Compose, migrations, app skeleton                        | Complete, verified                                                                                  |
| 1       | Identity, roles, organization context, local login, OIDC                        | Complete, verified                                                                                  |
| 2a      | Public asset-code library, categories, asset models, custom-field definitions   | Complete end to end, verified                                                                       |
| 2b      | Physical assets, custom-field values, consumable stock, movement ledger         | Complete end to end, verified                                                                       |
| 3       | Media storage (S3-compatible)                                                   | Complete, verified                                                                                  |
| 4       | Hierarchical locations and physical containment                                 | Complete, verified                                                                                  |
| 5       | Packing requirements and templates                                              | Complete, verified                                                                                  |
| 6       | QR scanning and initial asset-label output                                      | Implemented; automated verification complete, physical print/device acceptance pending              |
| 7       | Events, reservations, recursive expansion and conflict calculation              | Complete, verified                                                                                  |
| 8       | Checkout manifests, custody, consumable issue/return, PDF, return task creation | Complete, verified                                                                                  |
| 9.1     | Online return-audit execution and baseline reconciliation                       | Complete, verified                                                                                   |
| 10      | Finding review, lifecycle, repairs and seal administration                      | Complete, verified                                                                                   |
| 11      | Container packing sheets                                                        | Not started                                                                                         |
| 9.2-9.3 | Persistent scan outbox and queued audit photographs                             | Explicitly deferred until after Phases 10 and 11                                                    |
| 12-14   | Temporary access, search, hardening                                             | Not started                                                                                         |

Verified at the Phase 8 checkpoint:

- Backend: **390 tests, 0 failures**, against real PostgreSQL through Testcontainers.
- Frontend: **144 tests, 0 failures** (126 web, 11 api-client, 7 shared-ui), plus `format:check`,
  `lint`, `typecheck` and `build` clean. The focused desktop and mobile Playwright scanner journey
  has **2 passing tests**. `jibBuildTar` also succeeds with the matching frontend embedded.

Verified at the Phase 9.1 checkpoint:

- Backend: **397 tests, 0 failures**, including seven focused PostgreSQL audit regressions covering
  idempotency, immutable completion, bottom-up dependencies, cable swaps, manifest loss, tenant
  boundaries and consumable adjustments.
- Frontend: **148 tests, 0 failures** (130 web, 11 api-client, 7 shared-ui), with `format:check`,
  `lint`, `typecheck` and production `build` clean. Four Playwright checks pass across the asset
  scanner and online audit journeys on desktop and mobile; `jibBuildTar` succeeds with the matching
  frontend embedded.

Verified at the Phase 10 checkpoint:

- Backend: **413 tests, 0 failures**, against PostgreSQL. The Phase 10 coverage includes append-only
  resolution idempotency/conflicts, formal loss accounting, tenant and role boundaries, repair
  concurrency and availability, replacement identity, seal history, verification invalidation,
  reviewed-child unlocking and fresh audit attempts.
- Frontend: **152 tests, 0 failures** (134 web, 11 api-client, 7 shared-ui), with `format:check`,
  `lint`, `typecheck` and production `build` clean. Six Playwright journeys pass across scanner,
  online audit and finding-review flows on desktop and mobile; `jibBuildTar` succeeds with the
  matching frontend embedded.

## 2. How to verify this yourself

Docker Desktop does not work on the primary development workstation (no WSL2 backend), so backend
integration tests run against the project's Ubuntu development server over an SSH tunnel. The
procedure is in `LOCAL_DEV.md` (untracked, developer-specific).

```bash
# backend, from backend/
DOCKER_HOST=tcp://localhost:23750 TESTCONTAINERS_HOST_OVERRIDE=10.0.13.15 \
  TESTCONTAINERS_CHECKS_DISABLE=true ./gradlew spotlessCheck build -PskipFrontend=true

# frontend, from frontend/
pnpm format:check && pnpm lint && pnpm typecheck && pnpm test && pnpm build

# full production image, from backend/ (compiles the frontend into static resources)
./gradlew jibBuildTar
```

`-PskipFrontend=true` is for backend-only iteration. `jib` and `jibBuildTar` deliberately **fail**
when it is set, because ADR-0004 requires that a production image can never be assembled without the
matching frontend.

Integration tests must never be weakened to H2 or skipped to obtain a green build: the development
policies require real PostgreSQL, and every defect listed in section 7 was found by actually running
things.

## 3. Architecture decisions recorded during implementation

- [ADR-0002](adr/ADR-0002-public-asset-code-checksum.md) was **written during Phase 0** because the
  plan left the checksum construction undecided and Phase 2 could not proceed without it. It defines
  the GF(2^5) construction, normalization, validation order and authoritative test vectors.
- ADR-0001 carries a dated **amendment replacing MinIO with Garage** as the first-party self-hosted
  object store, because MinIO's images are no longer anonymously pullable from Docker Hub or Quay.
  Application code is unaffected: it speaks only the S3 API behind a `MediaStorage` interface.

## 4. What exists, by area

### Backend (`backend/`)

One Gradle project, Java 25, Spring Boot 4.1.1, Spotless with palantir-java-format, Gradle
dependency locking (`gradle.lockfile` is committed), Jib with a digest-pinned `eclipse-temurin:25-jre`
base image.

Migrations (Flyway owns all schema; applied migrations are immutable):

| Migration | Contents                                                                                            |
| --------- | --------------------------------------------------------------------------------------------------- |
| `V1`      | `organization`                                                                                      |
| `V2`      | Spring Session JDBC schema                                                                          |
| `V3`      | `app_user`, `organization_membership`, `external_identity`, `activity_log`                          |
| `V4`      | `category`, `asset_model`, `model_custom_field`, `model_custom_field_option`                        |
| `V5`      | `physical_asset`, `asset_custom_field_value`, `asset_state_history`, `asset_model.next_unit_number` |
| `V6`      | `consumable_stock_balance`, `stock_movement`                                                        |
| `V7`-`V8` | S3-compatible media metadata, layout images and deferred-cleanup support                            |
| `V9`      | Organization locations, physical containment and generalized stock places                           |
| `V10`     | Packing templates, copy-on-apply requirements and immutable packing history                         |
| `V11`     | Events, booking lines, immutable reservation revisions/claims and event history                     |
| `V12`     | Immutable checkout manifests, custody/return operations and audit-task dependency foundation        |
| `V13`     | Online container-audit facts, frozen expectations, scans, observations, findings and operation IDs  |
| `V14`     | Append-only finding resolutions, repair/seal/verification history, formal manifest accounting and audit-attempt projection |

API surface is under `/api/v1`. The OpenAPI document at `/v3/api-docs` (enabled only under the `dev`
profile) is the machine-readable contract; treat it as authoritative over any list here. Broadly:
session, users, external identities, categories, asset models, model custom fields and options,
assets (including bulk creation and public-code lookup), and consumable stock with its movement
ledger.

Phases 3-5 add S3-only media uploads and safe same-origin streaming, organization-scoped location
paths and direct containment, and packing requirements with a preview (not packing sheets).
Packing requirements are versioned and archived;
templates copy rows onto a named container rather than live-linking them. The preview evaluates only
direct active contents, reserves exact assets before interchangeable pools, exposes extras/misplaced
pinned items, and can evaluate consumable amounts from a direct balance or an ephemeral observation.
Actual and template requirement changes retain immutable before/after JSON snapshots, including
referenced names and codes. Tracking-mode changes are blocked by current or archived actual/template
requirements and their history. Phase 8 locks packing mutations while a container or ancestor is in
checkout custody. Phase 10 invalidates the applicable seal and verification assertions after packing
or verified-content changes.

Phase 6 adds generated PDF asset labels for both specified A4 stocks, calibration sheets, UTF-8
P-touch CSV, and raw-code QR round-trip verification. Phase 7 adds timestamped Draft/Reserved/
Cancelled events, recursive container and exact-requirement expansion, interchangeable model
capacity, carried and separately issued consumable snapshots, explainable conflicts, immutable
reservation revisions/history, retry-safe creation, and recalculation after relevant packing,
placement, lifecycle, model and stock changes. Reserved events that become conflicted are retained
and marked `ATTENTION_REQUIRED`; completed snapshots are never rewritten.

Phase 8 adds immutable checkout manifests with frozen event, asset, physical-containment and
consumable display data; exact interchangeable replacement selection; retry-safe checkout and return
operations; separately issued versus container-carried stock semantics; and deterministic PDF
regeneration. Partial returns preserve progress, parent-container returns expand through frozen
containment, unused separately issued stock can return to a selected stock place, and explicit
accounting completion retains the issued-minus-returned consumption result. Returned containers
create real ready/blocked audit tasks with direct-child dependencies for Phase 9.

Phase 9.1 adds online-only audit execution. An operator starts a ready task by scanning its assigned
container, then works through frozen direct-content expectations. Exact assets are matched before
interchangeable model quantities; duplicate scans are idempotent, scans can be undone or moved within
the batch, and unexpected/misplaced, missing, damaged, unreadable-label and unknown-code observations
are recorded as findings. Consumable confirmations are separate from serialized scans; only Owners and
Deputies can submit a balance-changing observed amount through the immutable stock-movement ledger.
Completion requires a matching closing container scan, preserves completed observations, updates direct
verified placement, and unlocks parents bottom-up. Persistent IndexedDB queueing, retry/backoff and
photo recovery remain Phase 9.2/9.3 work, not implemented behavior.

Phase 10 adds a strictly additive V14 migration: completed audit findings, scans and manifests remain
immutable while one final idempotent resolution is appended per finding. Owner/Deputy review can
record safe placement, lifecycle, repair, label and dismissal outcomes; formal loss/destruction
accounting releases custody without fabricating a physical return. Repairs are retained as history,
block reservation and checkout while open, and close with a resulting condition. Individual
containers expose sealability plus append-only applied, verified, broken and invalidated history.
Breaking a seal invalidates verification, preserves the completed attempt, creates a numbered fresh
attempt and reblocks dependent parents. Effective audit and event state is recalculated after review,
repair and seal actions, and replacement assets always receive a new identity and public code.

Authorization is enforced in the **service** layer, not by URL matchers. Owner administers users and
identities; Owner or Deputy administers the catalog, assets, media, locations, containment, stock and
packing requirements; every authenticated role may read. Cross-organization records are reported as
missing, never as forbidden, so existence does not leak.

### Frontend (`frontend/`)

pnpm workspace: `@bigcontainers/web`, `@bigcontainers/api-client`, `@bigcontainers/shared-ui`.
React 19, Vite 8, TypeScript 6.0.3 (**not** 7.x — `typescript-eslint` declares `<6.1.0`), MUI 9
behind owned wrappers and design tokens, `vite-plugin-pwa`/Workbox, React Router 7.

Built: the app shell, a connectivity capability with its web implementation, the ADR-0002 asset-code
normalization and checksum with its own test suite, the Phase 1 identity UI (sign-in with the optional
OIDC provider button, session restoration, role-gated routing, Owner-only user administration and
external-identity management), and the complete Phase 2 inventory workbench. Phase 2 covers category
color/editor/archive controls; model creation and guarded editing of tracking, quantity and container
settings; custom fields and dropdown choices; individual and bulk serialized-asset creation; asset
details, state history and manual code lookup; consumable balances and immutable movement-ledger
actions. Route and lookup responses are guarded against stale asynchronous results, and client
validation limits stock values to non-zero three-decimal quantities and bulk creation to 1-500 units.

Phases 3-5 add ordered reference-photo management, location and nested-container placement, and a
packing workbench. Owners and Deputies can manage exact, interchangeable-model and consumable
requirements, maintain reusable copy-on-apply templates, enter non-persistent observed consumable
amounts and inspect grouped complete, missing, extra and misplaced results. Archived rows remain
visible and can be restored with optimistic-version checks.

Phase 6 adds continuous camera scanning with native detection plus a pinned ZXing fallback, permanent
manual entry, scanner results/restore, bulk/reprint label calibration and PDF/CSV downloads. Phase 7
adds an Events month/list board and detail workflow for draft editing, equipment and consumable
lines, availability preview, reservation, structured conflicts/warnings, attention state,
cancellation and immutable activity history. Only Owners and Deputies mutate events; every permanent
role may read them.

Phase 8 adds a responsive event-handoff workflow for checkout selection, immutable manifest/PDF
access, partial asset and consumable returns, manual-code lookup alongside the camera scanner, final
return accounting and ready/blocked audit-task visibility. Operator access is limited to the
server-authorized checkout/check-in actions; reservation administration remains Owner/Deputy only.

Phase 9.1 adds a scanner-first audit task page, reached from each ready return task. It shows online
submission status, last scans/corrections and an expected direct-content ledger on mobile and desktop.
Frozen names, codes, consumable units, wrong-container destinations and compatible unmet-container
suggestions keep the scanner useful even if catalog names later change. Failed online submissions
retain their operation UUID for explicit retry, and cross-audit ownership conflicts offer an explicit
`Move scan here` action. The OpenAPI snapshot and generated TypeScript client were captured from a
live `dev` backend after V13 migrated a clean PostgreSQL database; the feature wrapper now uses the
shared generated client and types.

Phase 10 adds an Owner/Deputy review queue with frozen event, audit-task, container and finding
context, server-defined applicable decisions, deliberate confirmation for permanent outcomes and
payload-stable retry IDs. Asset detail exposes repair history and open/close actions, current seal and
verification state, seal controls, replacement lineage and replacement creation. Event and audit
views link directly into review. The updated OpenAPI snapshot and generated client were captured from
a live `dev` backend after V14 migrated a clean PostgreSQL database.

API types are **generated** from the backend's OpenAPI document into
`packages/api-client/src/generated/`. Hand-written duplicates of generated request/response shapes
are forbidden by policy. Regenerate with the package's `generate` script against a running backend.

### Infrastructure (`infrastructure/`, `.github/`)

Compose stack: application, PostgreSQL 18.6, and Garage behind a Compose profile so an external
S3-compatible endpoint can replace it. Garage needs a documented one-time cluster-layout, bucket and
key initialization before it serves traffic; `infrastructure/compose/README.md` has the exact
commands, which were executed against a real container rather than transcribed.

GitHub Actions: a reusable validation workflow shared by pull-request checks and the publish
workflow, so publication cannot skip tests. All third-party actions are pinned to full commit SHAs.
Playwright is deliberately **not** yet run in CI — it needs a full application-under-test
environment; the reasoning is recorded in the workflow itself.

## 5. Deferred work with known plug-in points

These are **not** oversights. Each is deferred because the table or phase it depends on does not
exist yet, and each is documented at its call site in code.

| Deferred                                          | Where it plugs in                                                                                                      |
| ------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Automatic email linking toggle                    | A static environment variable, not a runtime Owner-toggled setting, because no settings store exists yet.              |
| Cross-model "metadata incomplete" dashboard view | Per-asset and per-model visibility exists; the organization-wide operational view belongs to Phase 13.                 |

## 6. Known characteristics worth knowing before changing things

- **Spring Boot 4 splits autoconfiguration into per-technology modules.** Declaring a bare
  third-party artifact leaves the technology present but entirely unconfigured, with its
  `application.yml` settings silently inert and nothing failing loudly. Always confirm the matching
  `spring-boot-*` module appears in `gradle.lockfile`, and prefer the official starter.
- **The `Clock` bean ticks in whole microseconds.** PostgreSQL `timestamptz` stores microseconds
  while `Instant` holds nanoseconds, so an untruncated value stops equalling the row read back.
  Always take the injected `Clock`; never call `Instant.now()`.
- **pgjdbc cannot bind a bare `Instant`** in raw SQL, and will not read `timestamptz` directly back
  into one. Convert to `OffsetDateTime` at the boundary. Documented in
  `ConsumableStockLedgerRepository`.
- **`AbstractIntegrationTest` uses the singleton-container pattern deliberately.** Do not add
  `@Testcontainers` or `@Container`: that pair stops the shared container when the first test class
  finishes, and every later class then fails with order-dependent "Connection refused".
- **An entity throwing `IllegalArgumentException` surfaces as an opaque 500**, because the exception
  handler maps only the `ApplicationException` hierarchy. Throw the application exception or
  translate at the service boundary.
- A column type that disagrees with its entity mapping fails `ddl-auto=validate` and takes down
  **every** Spring context, not just one test.

## 7. Verification performed beyond the test suites

Test suites alone repeatedly reported green while real defects were present, so each phase was also
exercised against a running application and a real database.

- Local and OIDC identity: CSRF enforcement, byte-identical non-enumerating login failures, session
  issuance and survival across a full page reload, last-Owner protection on both demotion and
  disabling, and a VIEWER blocked from Owner-only reads and writes — including by direct URL
  navigation, with no data leaked before the server refused.
- **OIDC anti-lockout** (ADR-0003's central guarantee): with `LOCAL_AND_OIDC` configured against an
  unreachable issuer, the application starts normally, the provider entry point returns a `503`
  problem document stating that local sign-in remains available, and an enabled local Owner still
  signs in. No client secret, issuer, client id or stack trace appears in the response.
- **Public asset codes, both directions.** 20,000 shared inputs produced identical check symbols in
  the Java and TypeScript implementations, both matching ADR-0002's published vectors, with
  identical normalization across every ambiguous-symbol and rejection case. Separately, ten codes
  minted by the backend were all validated by the frontend implementation. The two implementations
  were written independently from the ADR; agreement is therefore evidence rather than tautology.
- Manual code lookup distinguishes a transcription error (`400`, with the rejection reason, before
  any database access) from an unknown code (`404`), and resolves `5j3-h0f` to `5J3H0F`.
- **Consumable ledger reconciliation.** After a receipt, a transfer and a consumption, each balance
  equalled the sum of its own movement deltas. An over-issue was refused with `409`, decimal
  quantities were exact, and low-stock derivation flipped correctly around its threshold.
- Concurrency is covered by tests using real parallel threads released together against real
  PostgreSQL: concurrent bulk asset creation never duplicates unit numbers, and of eight concurrent
  issues of an entire balance exactly one succeeds while the rest are refused, leaving the balance
  non-negative and consistent with its ledger.
- **Packing acceptance.** Exact requirements are matched before interchangeable pools, externally
  pinned assets are misplaced rather than reused, only direct active contents count, and observed
  consumable amounts do not mutate stock. Concurrent exact pins produce one winner, template copies
  are atomic and independent, and immutable history retains before/after JSON.
- **Phase 6 label/scanner acceptance.** Rendered PDFs for both A4 presets and the calibration sheet
  were visually inspected, and rendered QR output decodes to the canonical code. CSV quoting,
  Unicode and CRLF output are covered. Physical sheet alignment, printed mobile scanning and the
  real P-touch import remain manual acceptance checks.
- **Phase 7 reservation acceptance.** PostgreSQL tests cover recursive ancestor/descendant holds,
  external exact requirements, half-open time ranges, interval-peak model capacity, interchangeable
  replacement, global consumable ATP, carried/source stock, concurrency, cancellation, immutable
  revisions/history, tenant/role boundaries, retries and recalculation after inventory changes.
- **Phase 8 checkout and return acceptance.** PostgreSQL tests cover immutable frozen manifests,
  selected interchangeable replacements, nested physical-return expansion, direct audit-task
  dependencies, tenant/role/idempotency boundaries, concurrent final returns, consumable issue and
  return accounting, custody release and checked-out source-container exclusion. PDF tests cover
  frozen content and multi-page overflow.

## 8. Commit history caveat

The implementation was committed in phase-sized groups **after** the fact, reconstructed from the
final tree rather than recorded as the work happened. Files touched by several phases therefore sit
in the earliest commit that needed them, carrying their final content. Individual intermediate
commits are consequently **not guaranteed to compile or pass tests on their own**; the final tree is
what was verified. Treat the history as a readable grouping, not a bisectable timeline.

## 9. Suggested next steps

1. Review Phases 6 and 7, including physical label-stock/mobile/P-touch acceptance.
2. **Phase 11** - printable container packing sheets.
3. **Phases 9.2 and 9.3** - persistent scan outbox and queued audit photographs.

Phase 9.1 is implemented end to end for online operation: audits freeze direct expectations at start,
enforce bottom-up execution, match exact requirements before model quantities, retain corrections,
reconcile exact event-manifest identities separately from container completeness, and transition clean
or finding-bearing returns appropriately. Browser-persistent outbox retry and queued photographs remain
the explicitly deferred Phase 9.2/9.3 work after Phase 11.

Before starting, run the verification commands in section 2 to confirm the tree is still green.
