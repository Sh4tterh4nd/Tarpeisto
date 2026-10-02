# Tarpeisto Implementation Status

Status: Living record of what exists, as of 2026-10-02

Purpose: This document records **what is actually built and verified**, so that a contributor (human
or agent) can continue the work without rediscovering it.

- [`SPECIFICATION.md`](SPECIFICATION.md) defines required behavior.
- [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) defines intended delivery order.
- **This document records reality**, including where reality deliberately departs from the plan.

Read it together with [`DEVELOPMENT_POLICIES.md`](DEVELOPMENT_POLICIES.md) and the accepted ADRs.

## 1. Summary

| Phase   | Scope                                                                           | State                                                                                  |
| ------- | ------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| 0       | Repository, build, CI, Compose, migrations, app skeleton                        | Complete, verified                                                                     |
| 1       | Identity, roles, organization context, local login, OIDC                        | Complete, verified                                                                     |
| 2a      | Public asset-code library, categories, asset models, custom-field definitions   | Complete end to end, verified                                                          |
| 2b      | Physical assets, custom-field values, consumable stock, movement ledger         | Complete end to end, verified                                                          |
| 3       | Media storage (S3-compatible)                                                   | Complete, verified                                                                     |
| 4       | Hierarchical locations and physical containment                                 | Complete, verified                                                                     |
| 5       | Packing requirements and templates                                              | Complete, verified                                                                     |
| 6       | QR scanning and initial asset-label output                                      | Implemented; automated verification complete, physical print/device acceptance pending |
| 7       | Events, reservations, recursive expansion and conflict calculation              | Complete, verified                                                                     |
| 8       | Checkout manifests, custody, consumable issue/return, PDF, return task creation | Complete, verified                                                                     |
| 9.1     | Online return-audit execution and baseline reconciliation                       | Complete, verified                                                                     |
| 10      | Finding review, lifecycle, repairs and seal administration                      | Complete, verified                                                                     |
| 11      | Container packing sheets                                                        | Complete, verified; physical paper/camera acceptance pending                           |
| QoL     | Inventory navigation, browsing, exact assignment, layouts and safer catalog UX  | Complete, verified                                                                     |
| 9.2-9.3 | Persistent scan outbox and queued audit photographs                             | Complete, automated verification passed                                                |
| 12      | Scoped temporary volunteer access and persistent permanent sign-in              | Complete, automated verification passed                                                |
| 13      | Archive, bounded search, operational workboard and complete CSV reports          | Complete, automated verification passed                                                |
| 14      | Production hardening and first stable release                                   | In progress; first security hardening task verified                                     |

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

Verified at the Phase 11 checkpoint:

- The server now renders direct-only container packing sheets as portrait A4 pages with duplicate
  landscape-A5 halves, category bars, bundled Roboto Mono, canonical-code QR, measured wrapping,
  column/font fitting and continuation pages. The backend suite has **429 tests in 55 suites, 0
  failures and 0 skips**, including eight PostgreSQL packing-sheet integration tests. PDF tests
  verify duplicate-half content, long unbroken text, multipage exact-asset completeness and
  decoding the QR from both rendered halves.
- The API contract and Packing panel download/retry state are implemented for every permanent
  role. The frontend has **156 unit tests** (138 web, 11 api-client, 7 shared-ui), and
  `format:check`, `lint`, `typecheck` and production `build` pass. Eight Playwright checks pass:
  the existing six scanner/audit/review journeys plus desktop and mobile mocked-route packing-sheet
  download journeys. `jibBuildTar` succeeds with the Phase 11 frontend embedded.
- Physical printer alignment and camera scanning of a printed packing sheet remain manual
  acceptance checks.

Verified at the inventory quality-of-life checkpoint:

- The backend suite has **459 tests in 57 suites, 0 failures and 0 skips** against PostgreSQL. It
  covers nullable model categories, Owner-only safe category deletion, bounded asset search,
  container exclusion from ordinary labels, and atomic exact-requirement assignment with rollback.
- The frontend has **165 unit tests** (147 web, 11 api-client, 7 shared-ui), with `format:check`,
  `lint`, `typecheck` and production `build` clean. All eight desktop/mobile Playwright journeys
  pass, and `jibBuildTar` succeeds with the matching frontend embedded.
- The app now has a desktop navigation rail and mobile drawer, separate model and asset tables,
  sortable/filterable inventory views, searchable exact-asset requirements, safer confirmations,
  optional `Default` categories, refined responsive details and immediate scanner startup.

Verified at the Phase 9.2/9.3 checkpoint:

- Backend: **474 tests in 60 suites, 0 failures, errors or skips**, with `spotlessCheck check`
  passing against PostgreSQL. Nine focused evidence integration tests cover authorization, tenants,
  immutable replay/checksum semantics, completed evidence, generic deletion protection, concurrent
  upload/completion, audit-row locking and losing S3-attempt cleanup with mocked `MediaStorage`.
- Frontend: **195 unit tests, 0 failures** (177 web in 37 files, 11 api-client, 7 shared-ui),
  with `format:check`, `lint`, all workspace typechecks and production PWA build passing. IndexedDB
  tests cover FIFO/reopen recovery, atomic staging/quota rollback, fenced leases, completion barriers,
  stale snapshot replies, persistent logout generations/tombstones, current CSRF and actor switches.
- **16 Playwright tests pass** on desktop and mobile browser configurations. These are real browser
  workflows with mocked API routes and real IndexedDB, including lost responses, queued photo bytes,
  reload recovery, concurrent tabs and account-switch isolation. They do not constitute physical
  phone/camera or printer acceptance.
- `jibBuildTar` succeeds with the matching compiled frontend embedded. The live backend OpenAPI
  document and generated TypeScript contract include evidence endpoints and operation linkage.
- Deploy the matching application image and apply the explicit Flyway migration step through V18
  before using audit evidence. Existing V1-V17 migration files remain unchanged.

Verified at the Phase 12 checkpoint:

- Backend: **496 tests in 64 suites, 0 failures or errors**, with `spotlessCheck check` passing
  against PostgreSQL. Coverage includes hashed shared invitations, distinct named actors,
  retry-safe redemption, fixed 24-hour expiry, tenant and service boundaries, CSRF, decoded QR
  links, scoped media, and revocation races during redemption, audit mutations and evidence uploads.
- Frontend: **205 unit tests, 0 failures** (187 web, 11 api-client, 7 shared-ui), with
  `format:check`, `lint`, workspace typechecks and production PWA build passing. **28 Playwright
  tests pass** across desktop and mobile configurations, including volunteer join, event waiting,
  offline replay, revocation, expiry and manager invitation issuance/revocation. These journeys use
  mocked API routes and real browser storage.
- A separate live mobile Chromium profile check against PostgreSQL confirmed that the same
  permanent user remains signed in after closing and reopening the browser. Permanent sign-in
  retains revocable JDBC cookie sessions with a configurable 30-day idle timeout and persistent
  cookie. Visible clients check every five minutes and on foreground/reconnect; each API request
  checks current account/membership state. Temporary grants retain their fixed 24-hour deadline.
- The live OpenAPI document and generated client were recaptured after V19; regeneration from the
  snapshot produces no drift. `jibBuildTar` succeeds with the matching frontend embedded.
- Apply the explicit Flyway migration step through V19 before deploying the matching image.
  Existing users must sign in once after the upgrade to receive the persistent cookie; existing
  cookies and session rows retain their prior lifetime. Physical phone/camera acceptance remains
  manual.

Verified at the Phase 13 checkpoint:

- Backend: **519 tests in 69 suites, 0 failures, errors or skips**, with `spotlessCheck check`
  passing against PostgreSQL. Archive checks cover zero stock, active operation dependencies,
  independent tenant boundaries, concurrent local-Owner protection, retry versions, closed event
  accounting and immutable audit history. Nine report checks also pass after the final contract-only
  CSV response annotation change.
- Frontend: **213 unit tests, 0 failures** (195 web in 42 files, 11 api-client, 7 shared-ui), with
  `format:check`, `lint`, workspace typechecks and production PWA build passing. All **38 Playwright
  tests pass** across desktop and mobile browser profiles. New journeys cover queue continuation,
  report retry/download bytes, archive/restore, preserved model sorting and filter continuations,
  and explicit account enabling after restoration.
- Live PostgreSQL checks traversed 605 serialized units without duplicates, exercised mutable
  cursor anchors, literal wildcard escaping, active string-field lookup and recursive location
  paths. Inventory CSV exported 609 model/asset rows across the 500-row boundary; the stock exports
  retained exact decimal quantities, and audit CSV retained scans, undo, findings and appended
  resolutions. The four export endpoints return UTF-8 CSV successfully. Live desktop/mobile
  profile renders of workboard, reports, asset filters, stock and users have no page overflow or
  browser errors; these are not physical phone or printer acceptance.
- The workboard provides eight organization-scoped action queues. Normal browsing and new
  selections exclude archives, while detail/history views preserve names. Completed audits use a
  separate archive record; later physical changes invalidate verification without reopening
  archived operational history. Restored permanent accounts remain disabled.
- The captured OpenAPI document describes the new filters, archive versions, queues and binary CSV
  downloads; regeneration produces no drift. `jibBuildTar` succeeds with the matching frontend
  embedded. The matching image applies additive V20 at startup during the normal backed-up upgrade;
  V1-V19 remain unchanged.
- Optional activity/history CSV remains deferred. The four required reporting exports are complete;
  PostgreSQL and media backups remain necessary. Container queue counts reuse the existing packing
  and reservation evaluator, whose inventory snapshots need production-volume profiling in Phase 14.

Verified at the first Phase 14 security checkpoint:

- Backend: **539 tests in 75 suites, 0 failures, errors or skips**, with `spotlessCheck check`
  passing against PostgreSQL. Coverage includes concurrent admission ceilings, exact expiry,
  bounded-state exhaustion/reclamation, client/user rotation, successful-login accounting, valid
  missing and foreign codes, GET/HEAD budgets and temporary-access denial before admission.
- Global account enable/disable and external-identity reads/link/unlink reject shared accounts
  after resolving the owned membership. Regressions preserve a real indexed session, persisted
  account/identity state and the other organization's earliest Owner OIDC outcome. Nested unlink
  rejects missing, foreign and mismatched targets with the same error semantics; the request URI
  remains reflected in the RFC 9457 `instance` field. Inactive identity reassignment also checks
  the previous user's organization ownership and shared-account status before exposing retained
  profile claims. Permitted local reassignment persists the new user mapping after reload.
- The [authorization matrix](SECURITY_AUTHORIZATION_MATRIX.md) reviews **168 concrete routes from
  166 declared method/path patterns**, including permanent roles, temporary scope, public access,
  CSRF and admission budgets. Its runtime MVC gate detects missing/stale/duplicate entries,
  regex widening, explicit methods and application handlers in subpackages without REST annotations.
- The corrected permanent-user fixture passes all **10 affected desktop/mobile Playwright tests**;
  frontend formatting, lint and workspace typechecks pass. The Phase 13 checkpoint above records
  the full frontend unit and browser suites. `jibBuildTar` passes with the matching frontend
  embedded; this security task requires no database migration or API contract change.
- Login admission defaults to 5 attempts per normalized username/client and 60 per client in
  15 minutes, counting successful attempts. Checked-code GET/HEAD defaults to 120 per org/user and
  600 per client per minute. Each store retains at most 10,000 digest keys, reclaims expired state
  and rejects admission at live capacity. Budgets are configurable and apply per JVM; restart and
  additional instances reset or separate rate state. Persistent JDBC sign-in remains independent.
- Remaining Phase 14 work includes dependency/container scanning, CI browser gates and immutable
  image-publication checks; reliability/index profiling, media orphan tooling and backup/restore
  and migration rehearsals; physical browser/camera/printer checks and the complete event rehearsal.
  Publication and deployment remain separate operator actions.

Verified at the obsolete packing finding checkpoint:

- Backend: **554 tests in 76 suites, 0 failures, errors or skips**, with `spotlessCheck check`
  passing against PostgreSQL. Fifteen reconciliation integration tests cover final-state callbacks,
  rollback, concurrent retries, cursor boundaries, role/CSRF enforcement and conservative exclusions.
- Frontend: **221 unit tests, 0 failures** (203 web in 43 files, 11 api-client, 7 shared-ui), with
  formatting, lint and workspace typechecks passing. The full suite passes with two test workers;
  the default concurrent run hit existing scanner and asset-page timeouts. All **42 Playwright
  tests pass** on desktop and mobile, including sweep-before-count ordering and read-only roles.
- Current packing mutations append attributed `DISMISS` resolutions for proven obsolete generated
  serialized shortages and direct extra/misplaced scans when the whole current packing is complete.
  Reconciliation evaluates the final outer transaction state, including template and exact-asset
  assignment operations; failures roll back both the mutation and its resolutions.
- Owner/Deputy workboard and review refreshes reconcile existing findings through bounded,
  tenant-bound cursor pages before reading the queues. Other roles never initiate reconciliation.
  Refresh failures offer retry, and identity changes cancel old replies and further sweep pages.
- Completed audit observations and reports retain their original facts and appended resolutions.
  Manual, damage, label, consumable and event-manifest discrepancies remain reviewable; inactive
  contents and active audit attempts prevent automatic dismissal. Reconciliation does not restore
  verification or release event custody.
- Live PostgreSQL acceptance scanned twenty cables against ten required, then raised the packing
  quantity to twenty: all ten extra findings disappeared from review and workboard counts while
  remaining in history and CSV. A repeated sweep added nothing, the invalidated audit task remained
  ready for fresh verification, and ten present against twenty required retained its shortage.
- The live OpenAPI snapshot and generated client include the new reconciliation POST; regeneration
  produces no drift. The authorization matrix covers **169 concrete routes from 167 declared
  patterns**. `jibBuildTar` passes with the matching production frontend embedded. No database
  migration or configuration change is required.

Verified at the asset-detail redesign checkpoint:

- Asset detail now groups identity, purchase date, condition/lifecycle, placement, container seal,
  open repair and custom values in an expanded public-code card beside reference/layout photos.
  Normal view is read-only; its menu opens Edit, repair, confirmed Archive/Restore and conditional
  Open seal. Edit contains section saves and packing/template management; every permanent role
  retains read-only state, repair and seal history.
- Stored multiline model descriptions retain their original text. Asset and model headings join
  display lines with commas, or spaces after a full stop, without changing capitalization.
- A separate Direct contents card uses the authoritative packing matcher: linked exact assets,
  expandable interchangeable counts/codes, orange extra/misplaced/inactive items first, red missing
  quantities with alert icons, and consumable units. Packing-sheet download remains at the top in
  both modes. Tenant-bound revision cursors page assets, requirements and stock balances separately
  at 100 rows each; changed packing requires refresh instead of mixing stale pages.
- Manual physical seal application is Owner/Deputy-only, version-guarded and repeat-safe for an
  already applied state. It appends actor/time history, invalidates verification when changed, and
  leaves completed audit observations intact. Placement/seal drafts retain the observed version;
  conflicts preserve other drafts and offer an explicit reload.
- Backend: **560 tests in 77 suites, 0 failures, errors or skips**, with `spotlessCheck check`
  passing against PostgreSQL. New regressions cover bounded paging, global exact pins, inactive
  contents, decimal stock, stale cursors, immutable completed audit facts and HTTP role/CSRF/tenant
  boundaries.
- Frontend: **230 unit tests, 0 failures** (212 web in 43 files, 11 api-client, 7 shared-ui), with
  formatting, lint and workspace typechecks passing. All **46 Playwright tests pass** on desktop
  and mobile, including edit failure/retry, contents expansion/paging, confirmed archive, read-only
  history and packing-sheet download. `jibBuildTar` passes with the matching production frontend.
- Live PostgreSQL/browser checks confirm exact/interchangeable matching, missing requirements,
  problem ordering, repeat-safe physical seal actions, repair open/close and preserved multiline
  storage. Desktop and phone Chromium screenshots were inspected in normal and Edit views; linked
  contents navigate to the respective asset, ordinary equipment hides seal state, and neither
  profile has horizontal overflow. Physical phone/camera/printer acceptance remains manual.
- The live OpenAPI document and generated client include packing contents and physical seal apply;
  regeneration from the snapshot produces no drift. The authorization matrix covers **171 concrete
  routes from 169 declared patterns**. No database migration or configuration change is required.

Verified at the packing-sheet presentation checkpoint:

- Each landscape A5 half now uses separate white, black-bordered organization and identity boxes.
  Organization branding comes from the current tenant record; identity shows the current container
  display name and actual model name, with canonical QR/public code on the right.
- A black-bordered contents table reaches the bottom printable A5 margin with Quantity, Item and
  Code columns. Exact items show quantity 1, asset name and code; interchangeable rows show grouped
  model quantity/name and blank code. Consumables retain decimal amounts and stock units.
- Direct nested-container identities remain clearly labeled and exact-required children are not
  duplicated. Grandchildren are not expanded. Measured cell wrapping, body fonts from 12 down to
  8 pt and repeated headers on duplicated continuation pages retain all required content.
- Verification: backend `spotlessCheck check` passed **563 tests in 77 suites**, with no failures,
  errors or skips; `jibBuildTar` passed with the matching production frontend. PDF checks cover
  both QR decodes, column positions, duplicate geometry, exact quantities/codes, tenant identity,
  direct-only contents and continuation fragments. Empty, mixed, long, overflow and tall-row
  PDFs were rendered and visually inspected; the reviewed mixed-sheet raster is pinned.
- The PDF route and generated API contract are unchanged. No migration, dependency or configuration
  change is required; deploy the matching application image for the new packing-sheet layout.

Verified at the workboard availability-diagnostics checkpoint:

- A completely packed container can still be blocked by an audit, repair or unreleased checkout
  custody. The workboard explicitly separates complete packing from blocked availability and
  reports the actual cause, including audits on containing containers, rather than calling every
  restriction an inactive asset. Booking diagnostics retain the affected public asset codes.
- Only the workboard's explanation filters derivative model-capacity warnings when distinct,
  otherwise active, unarchived and unpinned selected or contained candidates explain the entire
  deficit through audit, repair or custody restrictions. Real shortages and overlap-driven demand
  remain visible.
  The original availability decision and full event/checkout conflict set remain authoritative;
  reading the workboard neither completes audits nor releases custody.
- Verification passed: all 572 backend tests across 78 suites, Spotless and the production Jib
  image build. Nine new integration tests cover own and ancestor audits, repair, checkout custody,
  genuine shortages, overlapping demand, exact pins, inactive inventory, archived models and
  organization/role isolation. Existing checkout replacement and peak-demand regressions also pass.
- No API, frontend, migration, dependency or configuration change is required. Deploy the matching
  application image to receive the corrected explanations; existing operational holds remain.

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
- ADR-0001 carries a dated **amendment replacing Garage with SeaweedFS** as the first-party
  self-hosted object store. The Swarm stack creates its bucket automatically and uses external
  secrets; application code remains on the same S3 API behind a `MediaStorage` interface.
- [ADR-0006](adr/ADR-0006-temporary-volunteer-access.md) defines fixed-expiry, revocable event/batch
  invitations, named history actors, scoped audit/media projections and offline expiry enforcement.
  ADR-0003 now records persistent permanent-account sessions and live account/membership checks.

## 4. What exists, by area

### Backend (`backend/`)

One Gradle project, Java 25, Spring Boot 4.1.1, Spotless with palantir-java-format, Gradle
dependency locking (`gradle.lockfile` is committed), Jib with a digest-pinned `eclipse-temurin:25-jre`
base image.

Migrations (Flyway owns all schema; applied migrations are immutable):

| Migration | Contents                                                                                                                   |
| --------- | -------------------------------------------------------------------------------------------------------------------------- |
| `V1`      | `organization`                                                                                                             |
| `V2`      | Spring Session JDBC schema                                                                                                 |
| `V3`      | `app_user`, `organization_membership`, `external_identity`, `activity_log`                                                 |
| `V4`      | `category`, `asset_model`, `model_custom_field`, `model_custom_field_option`                                               |
| `V5`      | `physical_asset`, `asset_custom_field_value`, `asset_state_history`, `asset_model.next_unit_number`                        |
| `V6`      | `consumable_stock_balance`, `stock_movement`                                                                               |
| `V7`-`V8` | S3-compatible media metadata, layout images and deferred-cleanup support                                                   |
| `V9`      | Organization locations, physical containment and generalized stock places                                                  |
| `V10`     | Packing templates, copy-on-apply requirements and immutable packing history                                                |
| `V11`     | Events, booking lines, immutable reservation revisions/claims and event history                                            |
| `V12`     | Immutable checkout manifests, custody/return operations and audit-task dependency foundation                               |
| `V13`     | Online container-audit facts, frozen expectations, scans, observations, findings and operation IDs                         |
| `V14`     | Append-only finding resolutions, repair/seal/verification history, formal manifest accounting and audit-attempt projection |
| `V15`     | Optional asset-model categories while retaining tenant-safe category references                                            |
| `V16`     | Session invalidation after principal rename                                                                                |
| `V17`     | Standalone container-audit batches                                                                                         |
| `V18`     | Immutable finding-linked audit evidence, tenant-safe associations and source-operation linkage                             |
| `V19`     | Tenant-owned fixed-expiry invitations, attributable volunteer sessions and credential/membership-free temporary users      |
| `V20`     | Stock/user/event archive state, immutable-audit archive sidecar and archived stock/account database guards                  |

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
verified placement, and unlocks parents bottom-up. Phases 9.2/9.3 now add persistent IndexedDB queueing, retry/backoff and photo recovery.

Phase 10 adds a strictly additive V14 migration: completed audit findings, scans and manifests remain
immutable while one final idempotent resolution is appended per finding. Owner/Deputy review can
record safe placement, lifecycle, repair, label and dismissal outcomes; formal loss/destruction
accounting releases custody without fabricating a physical return. Repairs are retained as history,
block reservation and checkout while open, and close with a resulting condition. Individual
containers expose sealability plus append-only applied, verified, broken and invalidated history.
Breaking a seal invalidates verification, preserves the completed attempt, creates a numbered fresh
attempt and reblocks dependent parents. Effective audit and event state is recalculated after review,
repair and seal actions, and replacement assets always receive a new identity and public code.

Phase 12 adds shareable event/batch invitations and scoped volunteer audit access. Each redemption
creates a distinct named history actor without permanent membership or credentials. Scope, expiry
and revocation are checked at service boundaries and again under mutation locks. Historical response
copies redact unrelated destinations without rewriting stored observations; evidence transfer is
rechecked during finalization. Audit scans and findings expose their recording actor and display name.

Authorization is enforced in the **service** layer, with an additional deny-by-default HTTP allowlist
for temporary identities. Owner administers users and
identities; Owner or Deputy administers the catalog, assets, media, locations, containment, stock and
packing requirements; every permanent role may read. Cross-organization records are reported as
missing, never as forbidden, so existence does not leak.

### Frontend (`frontend/`)

pnpm workspace: `@tarpeisto/web`, `@tarpeisto/api-client`, `@tarpeisto/shared-ui`.
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

The post-Phase-11 quality-of-life milestone adds a persistent desktop navigation rail and mobile
drawer, distinct sortable/filterable model and physical-asset views, optional neutral `Default`
categories, guarded archive/delete actions, exact-asset search with optional atomic container
assignment, responsive record/photo layouts, collapsed packing-sheet details, and camera auto-start.
Containers use only the duplicated A5-on-A4 contents sheet and are excluded from ordinary label
exports. The OpenAPI snapshot and generated TypeScript client were recaptured from a live `dev`
backend after V15 migrated PostgreSQL.

Phases 9.2/9.3 add a Dexie/IndexedDB outbox partitioned by organization and user. Scans,
move/undo corrections, findings, consumable observations and photo uploads persist before replay.
FIFO retries use stable operation UUIDs and bounded backoff; failed work stays visible for retry or
cancellation. Pending scans do not mark expected requirements as found. An active audit can recover
its container/snapshot and pending work after reload during an outage, while all inventory routes
remain unavailable to the cached identity. Start and completion remain online operations.

A shared persistent lease, heartbeat and fencing token coordinate tabs; completion checks every
persisted operation for the audit, including failed photographs. Acknowledgement and authoritative
snapshot replacement are atomic. Snapshot revisions reject older GET replies from another tab.
Logout or account changes immediately stop replay and hide old queues, while retaining them for the
same actor. Cached identity generations reject late recovery writes after logout even before a
cross-tab notification arrives. Each replay verifies the live session and supplies the expected actor
and current CSRF; no credentials or CSRF tokens are stored in IndexedDB.

Optional PNG/JPEG photographs are staged atomically with their damage, unknown-item or exact-item
unreadable-label finding, including binary bytes and the precise source-operation dependency. Uploads
show progress and retry status; selected unstaged files and queued uploads block completion.
Uploaded evidence appears on the audit and Owner/Deputy review pages. V18 enforces tenant-safe,
append-only associations. S3 attempts use separate keys and finalize under organization/audit locks;
matching upload/checksum replay is accepted after completion and changed-content replay is rejected.

Phase 12 adds creation-only QR/link dialogs and invitation revocation on event and audit pages.
Volunteers join through a fragment token that is immediately removed from the address, enter their
name and receive an assigned-task workspace, including a waiting state before event returns exist.
Temporary routing prevents inventory browsing, and the outbox checks the fixed deadline during
recovery, recording and replay. Audit observations display the volunteer's name. Permanent session
restoration checks on foreground, reconnect and every five visible minutes preserve the current
identity through transient network failures while fencing late replies after logout/account changes.

API types are **generated** from the backend's OpenAPI document into
`packages/api-client/src/generated/`. Hand-written duplicates of generated request/response shapes
are forbidden by policy. Regenerate with the package's `generate` script against a running backend.

### Infrastructure (`infrastructure/`, `.github/`)

Docker Swarm stack: application, PostgreSQL 18.6, and SeaweedFS 4.47 with automatic bucket creation.
It uses external Swarm secrets rather than a `.env` file and constrains local state volumes to a
labeled node; `infrastructure/compose/README.md` documents the required setup and replacement with
external S3-compatible storage.

GitHub Actions: a reusable validation workflow shared by pull-request checks and the publish
workflow, so publication cannot skip tests. All third-party actions are pinned to full commit SHAs.
Playwright is deliberately **not** yet run in CI — it needs a full application-under-test
environment; the reasoning is recorded in the workflow itself.

## 5. Deferred work with known plug-in points

These are **not** oversights. Each is deferred because the table or phase it depends on does not
exist yet, and each is documented at its call site in code.

| Deferred                                         | Where it plugs in                                                                                         |
| ------------------------------------------------ | --------------------------------------------------------------------------------------------------------- |
| Automatic email linking toggle                   | A static environment variable, not a runtime Owner-toggled setting, because no settings store exists yet. |

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

1. Complete remaining physical label-stock/mobile/P-touch acceptance.
2. Perform the remaining Phase 11 physical print and camera acceptance.
3. **Phase 14** - security, reliability, deployment rehearsals and stable-release acceptance.

Phase 9.1 is implemented end to end for online operation: audits freeze direct expectations at start,
enforce bottom-up execution, match exact requirements before model quantities, retain corrections,
reconcile exact event-manifest identities separately from container completeness, and transition clean
or finding-bearing returns appropriately. Browser-persistent outbox retry and queued photographs are implemented in Phases 9.2/9.3
after the completed inventory quality-of-life milestone.

Before starting, run the verification commands in section 2 to confirm the tree is still green.
