# BigContainers Implementation Status

Status: Living record of what exists, as of 2026-09-26

Purpose: This document records **what is actually built and verified**, so that a contributor (human
or agent) can continue the work without rediscovering it.

- [`SPECIFICATION.md`](SPECIFICATION.md) defines required behavior.
- [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) defines intended delivery order.
- **This document records reality**, including where reality deliberately departs from the plan.

Read it together with [`DEVELOPMENT_POLICIES.md`](DEVELOPMENT_POLICIES.md) and the accepted ADRs.

## 1. Summary

| Phase | Scope                                                                                   | State                                                                                                              |
| ----- | --------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| 0     | Repository, build, CI, Compose, migrations, app skeleton                                | Complete, verified                                                                                                 |
| 1     | Identity, roles, organization context, local login, OIDC                                | Complete, verified                                                                                                 |
| 2a    | Public asset-code library, categories, asset models, custom-field definitions           | Complete end to end, verified                                                                                      |
| 2b    | Physical assets, custom-field values, consumable stock, movement ledger                 | Complete end to end, verified                                                                                      |
| 3     | Media storage (S3-compatible)                                                           | Complete, verified                                                                                                 |
| 4     | Hierarchical locations and physical containment                                         | Complete, verified                                                                                                 |
| 5     | Packing requirements and templates                                                      | Complete, verified                                                                                                 |
| 6     | QR scanning and initial asset-label output                                              | Implemented; automated verification complete, physical print/device acceptance pending                             |
| 7     | Events, reservations, recursive expansion and conflict calculation                      | Complete, verified                                                                                                 |
| 8-14  | Checkout, audits, findings, sheets, temporary access, search, hardening                  | Not started                                                                                                        |

Verified at the Phase 6-7 checkpoint:

- Backend: **378 tests, 0 failures**, against real PostgreSQL through Testcontainers.
- Frontend: **141 tests, 0 failures** (123 web, 11 api-client, 7 shared-ui), plus `format:check`,
  `lint`, `typecheck` and `build` clean. The focused desktop and mobile Playwright scanner journey
  has **2 passing tests**. `jibBuildTar` also succeeds with the matching frontend embedded.

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
requirements and their history. Checkout, seal assertion and audit state do not exist yet: their
future guards and invalidation must be integrated into the packing mutation transaction when those
lifecycles are implemented; Phase 5 does not claim to enforce nonexistent state.

Phase 6 adds generated PDF asset labels for both specified A4 stocks, calibration sheets, UTF-8
P-touch CSV, and raw-code QR round-trip verification. Phase 7 adds timestamped Draft/Reserved/
Cancelled events, recursive container and exact-requirement expansion, interchangeable model
capacity, carried and separately issued consumable snapshots, explainable conflicts, immutable
reservation revisions/history, retry-safe creation, and recalculation after relevant packing,
placement, lifecycle, model and stock changes. Reserved events that become conflicted are retained
and marked `ATTENTION_REQUIRED`; completed snapshots are never rewritten.

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

| Deferred                                                          | Where it plugs in                                                                                                                                                                                                                                                                                            |
| ----------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Packing checkout guard and seal invalidation                       | Integrate at the organization-locked packing service mutation boundary when checkout and seal state exist. No substitute Phase 7/9 tables or flags are present. |
| Stock movement audit references                                   | The Phase 7 event reference now has a tenant-scoped foreign key; the nullable audit reference receives its target in Phase 9.                                                                                                                                                                                |
| Automatic email linking toggle                                    | A static environment variable, not a runtime Owner-toggled setting, because no settings store exists yet.                                                                                                                                                                                                    |
| Cross-model "metadata incomplete" dashboard view                  | Per-asset and per-model visibility exists; the organization-wide operational view belongs to Phase 13.                                                                                                                                                                                                       |

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

## 8. Commit history caveat

The implementation was committed in phase-sized groups **after** the fact, reconstructed from the
final tree rather than recorded as the work happened. Files touched by several phases therefore sit
in the earliest commit that needed them, carrying their final content. Individual intermediate
commits are consequently **not guaranteed to compile or pass tests on their own**; the final tree is
what was verified. Treat the history as a readable grouping, not a bisectable timeline.

## 9. Suggested next steps

1. Review Phases 6 and 7, including physical label-stock/mobile/P-touch acceptance.
2. **Phase 8** - checkout, frozen manifests, consumable issue movements and return/check-in.

Before starting, run the verification commands in section 2 to confirm the tree is still green.
