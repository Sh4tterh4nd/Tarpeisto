# Tarpeisto Implementation Plan

Status: Phases 9.2 and 9.3 complete after inventory refinement; automated verification passed.

Depends on: [Functional specification](SPECIFICATION.md)

## 1. Delivery approach

Build Tarpeisto as a conventional layered Spring Boot application with one web/PWA frontend, one backend, and PostgreSQL. Start with the simplest controller-service-repository structure that keeps transport, business logic, and persistence separate. Do not introduce Spring Modulith, multiple backend projects, or separately deployed services unless later complexity provides a concrete reason.

Implementation should proceed in vertical slices. Each milestone must leave the application demonstrable and tested rather than creating all database tables first and postponing usable workflows until the end.

### 1.1 Guiding principles

- PostgreSQL is the source of truth.
- Domain invariants are enforced both in application services and database constraints where practical.
- Every write is organization-scoped from the first migration.
- Completed manifests and audits are append-only/immutable.
- Scanner operations are idempotent.
- The PWA remains usable during short network interruptions.
- PDF and label output is visually rendered and physically calibrated as part of testing.
- Media access uses one S3-compatible contract in every production environment; self-hosting uses SeaweedFS and may replace it with external object storage.

## 2. Phase 0: architecture and repository foundation

The repository is currently specification-only. Before product implementation, record the following Architecture Decision Records (ADRs).

### 2.1 Technology decisions

[ADR-0001](adr/ADR-0001-application-stack.md) records the accepted backend, storage, and frontend stack.
[ADR-0002](adr/ADR-0002-public-asset-code-checksum.md) records the accepted public-asset-code alphabet, checksum construction, normalization rules, and test vectors.
[ADR-0003](adr/ADR-0003-authentication-and-session.md) records the accepted local/OIDC authentication, account-linking, session, and recovery design.
[ADR-0004](adr/ADR-0004-container-delivery.md) records the accepted Jib, GitHub Actions, GHCR, and single-application-image delivery design.

Confirmed choices:

- Java 25 LTS
- Spring Boot 4.1.1 and Spring MVC
- Gradle 9.8.0 through the checked-in wrapper, using Kotlin DSL
- PostgreSQL 18 (initial image baseline 18.6)
- S3-compatible media storage, with AWS S3 and self-hosted SeaweedFS as the initial targets
- Google Jib Gradle plugin 3.5.4 for one Spring Boot plus compiled-frontend OCI image
- GitHub Actions for CI and GitHub Container Registry for image publication

Accepted frontend choices:

- React with TypeScript and Vite
- React Router for client-side navigation
- Material UI behind Tarpeisto-owned shared components and design tokens
- A pnpm workspace with exactly pinned dependency versions
- `vite-plugin-pwa`/Workbox for the application manifest, service worker, and static asset cache
- IndexedDB through Dexie for the short-outage scan and upload queue
- A feature-detected native `BarcodeDetector` fast path with a maintained JavaScript decoder fallback

ADR-0001 also proposes the data-access, session, API, PDF, QR, and test tooling. Selection criteria remain:

- Produces one straightforward application deployment.
- Strong PostgreSQL transaction and migration support.
- Reliable mobile-camera access and PWA support.
- Server-side PDF creation or an equally deterministic alternative.
- Mature security and validation libraries.
- Good integration-test ergonomics with a real PostgreSQL database.
- No dependency on a proprietary backend platform.

### 2.2 Proposed repository shape

Preserve these responsibilities. The concrete layout follows the established Leirly conventions while keeping only the pieces this smaller application needs:

```text
Tarpeisto/
|-- AGENTS.md
|-- README.md
|-- backend/                   # one Gradle/Spring Boot project
|   |-- build.gradle.kts
|   |-- settings.gradle.kts
|   `-- src/
|-- frontend/
|   |-- apps/
|   |   `-- web/               # the single React PWA
|   `-- packages/
|       |-- api-client/        # generated OpenAPI types and client wrapper
|       `-- shared-ui/         # design tokens and owned MUI wrappers
|-- infrastructure/
|   |-- compose/
|   `-- container/
`-- docs/
    |-- SPECIFICATION.md
    |-- IMPLEMENTATION_PLAN.md
    |-- DEVELOPMENT_POLICIES.md
    `-- adr/
```

The production build copies the compiled `frontend/apps/web/` output into the backend's static resources; it remains one deployable application. Do not create a second frontend app or another shared package until a real boundary requires it.

Backend production code starts under `io.kellermann.tarpeisto` with global `controller`, `service`, `repository`, and `model` packages, plus narrowly scoped technical packages such as `config`, `security`, `storage`, `document`, and `exception`. The dependency direction is `controller -> service -> repository/model`; controllers do not call repositories directly.

### 2.3 Foundation deliverables

- Source formatting, linting, static analysis, and test commands
- CI pipeline running validation and tests
- Gradle frontend resource integration so every production backend/image build includes the matching React build
- Jib configuration with a digest-pinned Java 25 runtime base image, non-root runtime user, port, and OCI labels
- Pull-request image assembly validation with `jibBuildTar`, without registry publication
- Default-branch and version-tag GitHub Actions publication to `ghcr.io/<owner>/tarpeisto` using `GITHUB_TOKEN`
- Local development configuration
- Docker Swarm-compatible stack with application, PostgreSQL, and SeaweedFS
- Configuration that replaces SeaweedFS with an external S3-compatible endpoint
- Environment-variable schema with startup validation
- Health/readiness endpoint
- Structured logging with request/trace IDs
- Migration runner and documented rollback policy
- One-time browser setup that atomically creates the initial organization and local Owner

### 2.4 Exit criteria

- A clean checkout starts locally with one documented command.
- CI runs on every branch/merge request.
- PostgreSQL migrations run against an empty database.
- A clean database redirects to setup and can create the initial Owner and organization without deployment configuration.
- No business features depend on globally hard-coded organization IDs.

## 3. Phase 1: identity, roles, and organization context

### 3.1 Data model

Implement:

- `organizations`
- `users`
- `organization_memberships`
- `external_identities`, uniquely keyed by issuer and subject
- Session/authentication tables required by the selected stack
- Initial activity-event infrastructure

Even though the initial UI exposes one organization, membership is still explicit.

### 3.2 Application behavior

- First-run setup page and public setup-status endpoint, available only until the first Owner exists
- Login/logout and secure session rotation
- Local login as the default and recovery method
- Adaptive versioned local-password hashing, login rate limiting, and non-enumerating failures
- Optional generic OIDC login using issuer discovery and Spring Security OAuth 2.0 Client
- OIDC callback-to-session exchange with provider tokens handled only server-side and not retained beyond login unless later functionality requires them
- Owner-managed external-identity linking; verified-email linking only when explicitly enabled and unambiguous
- `LOCAL_ONLY` and `LOCAL_AND_OIDC` deployment modes
- Protection against removing the last usable local Owner recovery method
- Server-side active-organization context
- Role checks for Owner, Deputy, Operator/Auditor, and Viewer
- Shared permission test helpers
- Owner-only role management page sufficient for permanent accounts

### 3.3 Security tests

- A user cannot read or mutate a different organization's records.
- Client-provided organization IDs cannot override session organization.
- Every mutating endpoint denies insufficient roles.
- Activity entries identify the acting user.
- `(issuer, subject)` cannot be linked to two users, and mutable email changes do not alter identity.
- Unverified or ambiguous email claims cannot auto-link accounts.
- Disabled local users remain denied after successful provider authentication.
- Provider failure does not prevent an enabled local Owner from signing in.
- Local and OIDC sessions receive identical CSRF and authorization enforcement.

### 3.4 Exit criteria

- One Owner can sign in and create Deputy, Operator/Auditor, and Viewer users.
- The same internal user can sign in through a configured OIDC identity without changing organization membership or role.
- Role-protected placeholder actions behave correctly.
- Cross-organization integration tests pass even though no organization switcher exists.

## 4. Phase 2: catalog, serialized assets, and consumable stock

### 4.1 Data model

Implement migrations in dependency order:

1. `categories`
2. `asset_models`
3. `model_custom_fields`
4. `model_custom_field_options`
5. `physical_assets`
6. `asset_custom_field_values`
7. `consumable_stock_balances`
8. `stock_movements`
9. Asset lifecycle/condition history
10. Generic activity records

Add composite same-organization keys/foreign keys where supported.

### 4.2 Public-code library

Create a separately tested domain library that:

- Generates five cryptographically random Crockford Base32 data symbols.
- Produces one order-sensitive Base32 checksum symbol.
- Formats the result as one six-character string.
- Normalizes lowercase, spaces, hyphens, `O`, `I`, and `L`.
- Validates checksum before lookup.
- Supports longer future data portions without changing existing codes.
- Retries database uniqueness conflicts safely.

Tests must include:

- Every alphabet symbol
- Ambiguous-character normalization
- Single-character substitutions
- Adjacent transpositions
- Invalid lengths and symbols
- Large randomized/property-based test sets
- Database collision retry behavior

### 4.3 Catalog UI

- Category list/editor with color picker and contrast preview
- Model list/detail/editor
- Model description and replacement URL
- Tracking mode: `SERIALIZED_ASSET` or `QUANTITY_STOCK`
- Stock unit and optional low-stock threshold for quantity models
- `Can contain assets` capability
- Model custom-field editor for String, Dropdown, and Date
- Model page that shows definitions but never unit values
- Archive filters

### 4.4 Asset UI

- Create one unit
- Bulk-create numbered units
- Automatically assign model-local unit numbers
- Set condition and optional purchase date
- Render model-defined fields dynamically
- Require every active custom field value
- Show metadata-incomplete status for older units after a new field is added
- Asset detail with model information, code, condition, lifecycle, and activity
- Manual public-code lookup

### 4.5 Consumable stock UI and service

- Create quantity-tracked models without per-unit asset rows or codes
- Receive stock into a direct location or container
- Transfer stock transactionally between stock places
- Record event issue/return, consumption, and authorized adjustment movements
- Show current balances and immutable movement history
- Prevent normal negative balances and require an Owner/Deputy reason for corrections
- Show quantity stock on container and location detail pages

### 4.6 Exit criteria

- Create `UniFi AP-HD`, define Serial Number and MAC Address, and bulk-create ten units.
- Every unit receives a distinct checked code and model-local number.
- Values appear only on physical asset pages.
- Invalid or mistyped checksums are rejected before lookup.
- Lost/destroyed/retired filters behave as specified.
- Create `Gaffer tape 50 mm` measured in rolls, receive and transfer stock, and inspect its ledger without creating asset codes.
- Quantity models cannot be container-capable or define per-unit custom fields.
- Concurrent issues cannot spend the same available stock twice.

## 5. Phase 3: media storage and reference photographs

### 5.1 Storage abstraction

Define one application-owned interface supporting S3-compatible storage (AWS S3 and SeaweedFS):

- Organization-prefixed object keys
- Streaming upload/download
- Content-length limits
- MIME and magic-byte verification
- Hash/checksum metadata
- Deletion only through authorized domain operations

### 5.2 Media model and UI

- Model primary image
- Optional individual asset image
- Multiple ordered container-layout images with captions
- Thumbnail generation strategy
- Reordering and primary-image selection
- Safe image display URLs

Phase 9.3 adds immutable audit evidence uploads on the same abstraction.

### 5.3 Exit criteria

- The same application build works with SeaweedFS and external S3-compatible storage.
- Invalid file types and oversized uploads are rejected.
- Container images can be captioned `Bottom layer` and `Top tray` and reordered.

## 6. Phase 4: hierarchical locations and physical containment

### 6.1 Location implementation

- Location CRUD and archive behavior
- Parent selection
- Cycle prevention in a transaction
- Tree and breadcrumb UI
- Effective-path query

### 6.2 Containment implementation

- Nullable current parent container on physical assets
- Direct location versus container exclusivity
- Parent model must be container-capable
- Same-organization constraints
- Transactional descendant-cycle check
- Move asset/container action
- Effective location inherited through outermost container
- Container detail showing current direct contents

### 6.3 Tests

- Deep valid nesting
- Self-parent rejection
- Indirect cycle rejection
- Cross-organization rejection
- Moving a parent changes descendant effective paths
- A non-container asset cannot receive children

### 6.4 Exit criteria

- Create `HQ / Room 13 / Shelf A`.
- Place a pallet on the shelf, a flightcase on the pallet, and an AP in the flightcase.
- Display the complete effective path without duplicating direct locations on descendants.

## 7. Phase 5: packing requirements and templates

### 7.1 Data model

Implement:

- `packing_templates`
- `packing_template_requirements`
- `container_requirements`
- Requirement archive/history records

Requirement constraints:

- `SPECIFIC_ASSET`: asset required, quantity one
- `MODEL_QUANTITY`: model required, positive quantity
- `CONSUMABLE_QUANTITY`: quantity-stock model required, positive decimal amount in the model's stock unit
- One active exact requirement per physical asset across the organization
- Container and referenced model/asset belong to the same organization

### 7.2 Domain services

- Add direct requirements
- Copy template requirements onto a container
- Detect pinned assets
- Calculate eligible model pools
- Match a scan using exact-before-model rules
- Evaluate consumable minimums separately through balance confirmation or observed quantity
- Detect complete, missing, extra, and misplaced contents
- Prevent requirement changes while checked out
- Invalidate relevant verified/sealed assertions after packing changes

### 7.3 UI

- Container individual name required
- Add by model and quantity
- Add a specific asset by search or scan
- Add a consumable model and required amount
- Clear distinction between exact and interchangeable requirements
- Packing preview grouped like the eventual printed sheet
- Create and apply optional templates
- Template application copies rather than live-links requirements

### 7.4 Critical tests

- AP 1 pinned to Network Box 1 cannot satisfy `Any AP x 5` in Mobile Net Large.
- Five interchangeable cables can be fulfilled by any five eligible numbered units.
- A consumable requirement can be confirmed or counted without any per-unit QR scan.
- An asset cannot be exactly required by two containers.
- A template edit does not silently alter containers previously created from it.

### 7.5 Exit criteria

- Configure the complete cable-box and network-box examples from the specification.
- Configure consumable tape and cable-tie requirements alongside serialized contents.
- Packing completeness can be calculated without running an event.
- Owners/Deputies can modify requirements; Operators cannot.

## 8. Phase 6: QR scanning and initial label output

Implement labels before event workflows so real assets can be tagged during development.

### 8.1 Scanner

- Camera permission and device selection
- Continuous QR scan mode
- Debounce/duplicate handling
- Manual code input
- Normalization and checksum feedback
- Asset/container result page
- Browser fallback when the preferred scanning API is unavailable

### 8.2 Asset labels

- Deterministic QR generation using the raw public code
- 70 x 36 mm, 24-up A4 template
- 97 x 42.3 mm, 12-up A4 template
- Configurable margin, pitch, and gutter calibration
- Monospaced regular/bold font embedding
- Bulk generation, reprint, and skip-first-position support
- Category on A4 labels

### 8.3 P-touch CSV

- UTF-8 CSV export
- Stable documented columns
- Correct quoting of names containing commas/newlines
- `qr_value` equal to canonical code

### 8.4 Visual verification

- Render PDFs to images in automated tests.
- Compare representative output against approved snapshots.
- Print physical calibration pages on both A4 stock sizes.
- Verify QR readability after printing at intended size.

### 8.5 Exit criteria

- A generated label can be scanned back to the correct asset.
- Manual entry tolerates Crockford aliases and rejects checksum errors.
- Both A4 formats align with calibrated label sheets.
- P-touch CSV imports into the user's label workflow.

## 9. Phase 7: events, reservations, and conflict calculation

### 9.1 Data model

Implement:

- `events`
- `event_booking_lines`
- Consumable reservation and source-stock records
- Reservation-expansion/snapshot records as required by the chosen design
- Event activity/history

### 9.2 Reservation engine

- Draft does not hold inventory.
- Reserved events hold containers, exact assets, descendants, serialized model capacity, and separately requested consumable quantity.
- Recursive container expansion
- Exact-asset conflict detection
- Ancestor/descendant conflict detection
- Date-range overlap detection
- Model-capacity calculation excluding assets pinned elsewhere
- Consumable available-to-promise calculation subtracting all active planned issues, regardless of date overlap, with row locking/concurrency protection
- Source-container availability checks for separately booked consumables
- No double reservation for consumables already carried inside a booked container
- Individual booking effects on container completeness
- Recalculation after relevant packing additions/removals

The engine should expose explainable conflict results rather than a single Boolean, for example:

```text
Cannot reserve Mobile Net Large:
AP 1 is already reserved through Network Box 1 for an overlapping event.
```

### 9.3 Event UI

- Create/edit Draft
- Calendar/list views
- Add whole container
- Add individual asset
- Add planned consumable quantity from a selected stock place
- Reserve and display conflicts
- Cancel pre-checkout event
- Display packing additions/removal warnings affecting future reservations

### 9.4 Exit criteria

- All ancestor, descendant, exact, and model-capacity conflict scenarios have integration tests.
- Aggregate active consumable reservations cannot exceed available stock, even when their event dates do not overlap.
- A booked pallet blocks conflicting bookings for nested cases and assets.
- An individually booked interchangeable cable can be replaced in its box by another eligible unit.

## 10. Phase 8: checkout, manifests, PDF, and check-in

### 10.1 Checkout manifest

Implement immutable tables for:

- Manifest header
- Exact checked-out containers/assets
- Consumable quantity lines with unit, source stock place, and issued or container-snapshot semantics
- Source booking line and container context
- Checkout actor/time
- Explicit override reasons

### 10.2 Checkout flow

- Scan/select event lines
- Show seal state
- Offer optional full audit hook
- Use last verified exact contents for interchangeable requirements
- Resolve incomplete/unknown contents by scan or selection
- Require Deputy reason for permitted override
- Freeze manifest transactionally
- Mark physical custody checked out
- Write `EVENT_ISSUE` movements only for separately issued consumables; do not decrement stock merely because its container is checked out

### 10.3 Checkout PDF

- Event details
- Exact asset list grouped by booked container
- Consumable model, amount, unit, source, and whether it was separately issued or carried in a container
- Public codes
- Model and individual names
- Checkout actor/time
- Exceptions and override reasons
- Stable immutable regeneration from manifest data

### 10.4 Check-in

- Exact manifest reconciliation state per asset/container
- Partial-return progress
- `Audit now` and `Mark for later`
- Audit-task creation, not a passive Boolean
- Parent return expands descendant container tasks
- Unused separately issued consumables create `EVENT_RETURN` movements; issued minus returned remains event consumption history

### 10.5 Exit criteria

- Packing changes after checkout cannot alter the manifest or PDF.
- Consumable issued, returned, and consumed quantities remain reproducible from the movement ledger and immutable manifest.
- Partial return is visible and recoverable.
- Check-in of an outer container creates the expected audit batch and dependency graph.

## 11. Phase 9.1: online return audits and event reconciliation

This is the most operationally sensitive phase. Implement the server-authoritative online workflow first. It uses stable client operation UUIDs and server idempotency, but does not claim offline persistence.

### 11.1 Audit data model

Implement:

- `audit_batches`
- `audits`
- Audit dependency records
- Frozen expected-requirement rows
- Frozen expected consumable rows and audit confirmations/observations
- `audit_scans`
- Audit evidence media (implemented with queued upload recovery in Phase 9.3)
- Completion summaries
- Client operation IDs/idempotency records
- Standalone, scanner-launched container batches with no event booking or checkout manifest

### 11.2 Dependency engine

- Create audits for all descendant containers
- Direct-child dependencies only
- Unlock leaf audits first
- Unlock parent only after child clean/reviewed outcomes
- Invalidate readiness when a child is reopened or seal is broken
- Present blocked-reason details
- Reuse an incomplete task for the scanned container; otherwise build the graph from its current
  descendant containers and reject any subtree with unreleased event custody

### 11.3 Scanner workflow

Implement in this order:

1. Start by scanning target container
2. Expected direct-content table
3. Continuous scan and duplicate handling
4. Exact-before-model matching
5. Last-scanned card
6. Undo scan
7. Manual code and unreadable-label path
8. Damage note path; optional finding photographs are delivered in Phase 9.3
9. Missing confirmation
10. Extra/misplaced destination information
11. Cross-audit `Move scan here`
12. Consumable requirement confirmation, observed-quantity entry, or missing/low report
13. Final matching container rescan
14. Seal confirmation when Phase 10 introduces seal state
15. On a genuine equipment-scanner result, offer an authorized user a standalone container audit;
    keep an explicit action after decline and never auto-prompt after refresh/direct navigation

### 11.4 Persistent recovery follow-up

- Phase 9.2 owns the persistent IndexedDB scan/outbox queue, ordered retry/backoff, and restart recovery.
- Phase 9.3 owns audit evidence photographs, queued upload retry/progress, and restart recovery.
- Phases 9.2 and 9.3 replace the original online-only submission UI with persistent, account-scoped active-audit recovery. Starting and completing an audit still require a live session.

### 11.5 Event-wide reconciliation

- Track every manifest asset found anywhere in the batch
- Detect manifest items not found
- Detect scanned assets not on the manifest
- Keep container-completeness and event-completeness results separate
- Suggest containers with unmet compatible model requirements
- Prevent one asset from counting in two audits
- Reconcile consumable issue, return, and consumption independently of exact-asset reconciliation

### 11.6 Exit criteria

- The complete cable-swap scenario succeeds without manual reassignment.
- A replacement warehouse cable cannot hide the loss of an exact checked-out cable.
- A parent audit cannot start before child audits are cleared.
- Clean and finding-bearing completion paths work on both phone and desktop browsers.
- A volunteer can confirm consumables without counting each unit, while only an Owner/Deputy can approve a balance-changing audit adjustment.

## 12. Phase 10: findings, review, lifecycle, repairs, and seals

### 12.1 Finding workflow

- Review queue for Owners/Deputies
- Finding detail with audit context, photos, notes, and asset history
- Append-only resolutions
- Automatically dismiss obsolete generated serialized packing discrepancies when current contents fully match current requirements; retain manual, damage, label, consumable and exact-manifest review work and invalidated verification
- Resolution actions defined in the specification
- Event state recalculation after resolution

### 12.2 Lifecycle actions

- Mark lost
- Restore found lost asset
- Mark destroyed
- Retire
- Return to Active where permitted
- Exclude inactive lifecycle states from normal inventory

### 12.3 Repairs

- Create repair from damage finding or asset page
- Reference/description
- Open/close timestamps
- Asset unavailable while open
- Resulting condition on close
- Optional replacement link

### 12.4 Seal behavior

- Physical-container `sealable` setting
- Applied/broken/verified history
- Optional hidden/null seal reference
- Finish-audit confirmation
- Checkout seal display and confirmation
- Packing/content changes invalidate seal assertion

### 12.5 Exit criteria

- A finding can be resolved without mutating the completed audit.
- Lost assets remain historically visible and produce an alert when scanned.
- Open repairs prevent booking.
- A clean audit makes a container available; unresolved findings do not.

## 13. Phase 11: container packing sheets

### 13.1 Generator

- A4 with two identical A5-sized halves
- A5 landscape halves with a substantial category-color identity bar
- Bold container name on the left; category and a measured, at-most-three-line model-description preview beneath it, with an ellipsis when truncated
- QR and public code on the right
- Neutral `Default` identity bar when the container model has no category
- Direct packing requirements only, listed below the identity bar
- Grouped model quantities
- Exact asset names/codes
- Nested-container entries
- Ordered layout photographs are not embedded initially unless explicitly chosen later

### 13.2 Fit algorithm

1. Render list at 12 pt in one column.
2. Add columns while preserving 12 pt.
3. Reduce font stepwise to 8 pt minimum.
4. Add additional duplicated pages if content still overflows.
5. Never clip or silently omit a requirement.

### 13.3 Verification

- Snapshot/golden rendering tests
- Very long names
- Large quantities
- Many exact assets
- Multi-page overflow
- Category colors with readable title contrast
- QR scan from printed output

### 13.4 Exit criteria

- Representative small and large container sheets render without overlap.
- Every requirement appears exactly once per duplicated half.
- Multi-page sheets preserve the duplicate-half rule.

## 14. Quality-of-life inventory refinement (after Phase 11, before Phase 9.2)

This focused milestone improves established inventory workflows without changing the deferred persistent-audit or evidence-upload commitments.

### 14.1 Navigation, inventory, and category behavior

- Move desktop navigation to the left side and provide an equivalent accessible mobile drawer.
- Keep distinct Asset Models and Assets routes/views.
- Add sortable asset-model and asset tables. Model filters cover category and tracking mode; physical-asset filters cover category and equipment/container type.
- Require an explicit second confirmation before archiving an asset model.
- Make the asset-model category optional. Render a null category as `Default` with a neutral color without creating a synthetic category row.
- Keep category creation, editing, and archiving available to Owners and Deputies. Permit Owner-only hard deletion only when no asset model, including archived models, references the category; write the deletion activity snapshot before removing the unreferenced setup row.

### 14.2 Container requirements, detail presentation, and exports

- Replace manual exact-asset code entry with code/name search and selection.
- Offer a checkbox to assign the chosen exact asset to the container immediately. Requirement creation and current-parent assignment are one authorized, tenant-scoped transaction when selected.
- Present asset detail as title and compact multiline-description preview, then an expanded public-code card at left and reference/layout photos at right. Within the card show purchase date, two-column Condition/Lifecycle, direct location then parent container, container-only seal status, any open repair, and custom values last. Keep normal details read-only and omit empty custom-value/history sections.
- Put Edit, Open repair and confirmed Archive/Restore in the card's three-dot menu, with Open seal for sealable containers. Edit mode contains metadata, placement, state, repair and seal controls; manual seal application remains unverified and retains history.
- Present asset-model detail with reference photo on the right, category and unit-definition fields stacked at left, and physical units at the bottom.
- Show direct contents in a separate card: linked exact names/codes, expandable interchangeable counts with linked codes, orange extra/misplaced items first, red missing requirements with alert icons, and consumable quantities. Keep packing/template administration in Edit and packing-sheet download visible in both modes; any supplementary document preview remains collapsed.
- Exclude containers from ordinary asset-label PDF and P-touch export. Use only the container contents/packing sheet: duplicated A5 landscape halves on A4, a category-colored identity bar, and direct requirements below it.

### 14.3 Scanner startup and acceptance criteria

- Attempt camera startup immediately when an equipment-scanning flow opens; preserve permission-denied, unavailable-camera, retry/device-selection, and manual-entry fallbacks.
- Desktop navigation is left-sided and mobile navigation is usable through the drawer at phone width.
- Model and asset views remain distinct; both tables sort in both directions, models filter by category/tracking mode, and physical assets filter by category/equipment-or-container type.
- A null model category renders as neutral `Default` and is never persisted as a category record. A referenced category, including one referenced solely by an archived model, cannot be hard-deleted. An Owner can delete an unreferenced category only with an immutable activity snapshot; a Deputy cannot.
- Archiving a model requires the second confirmation; canceling it makes no mutation.
- Selecting an exact asset through search and enabling immediate assignment leaves both the requirement and current parent unchanged on any validation/authorization failure, and updates both on success.
- Asset, asset-model, and container detail layouts meet the stated placement and empty-section behavior at desktop and phone widths.
- Entering a scanner flow requests camera access without an extra start action and always leaves a usable fallback path.
- Container assets never appear in ordinary asset-label PDFs or P-touch CSV. Container sheets preserve two identical A5 landscape halves per A4 page, show the required identity bar, list direct requirements exactly once per half, and retain the existing fit/overflow guarantees.

## 15. Phase 9.2: persistent audit scan outbox (after quality-of-life refinement)

- Persist pre-generated operation UUIDs and scan/correction mutations in IndexedDB.
- Retry in order with bounded exponential backoff and explicit failed/synchronizing status.
- Block completion while local operations remain unsynchronized.
- Recover queued work after refresh or PWA restart without double-applying server mutations.
- Use Dexie/IndexedDB FIFO partitions by organization and actor, immutable command bodies, stable UUIDs and bounded retry delays. Persist before sending; failed storage must remain visibly unsaved.
- Keep only active-audit/container snapshots for offline recovery. Pending scans never satisfy authoritative requirements. No inventory browsing or completion is available offline.
- Coordinate drain and completion across tabs with an atomic lease, heartbeat and fencing token. Completion checks persisted scans, corrections and photos while holding that lease.
- Verify the live actor before replay and include the expected actor fence and current CSRF on each send. Logout/account changes stop and hide old work across tabs; a persistent identity generation prevents late requests from re-enabling recovery.

## 16. Phase 9.3: queued audit evidence photographs (after Phase 9.2)

- Persist evidence-upload metadata and binary staging safely for short outages.
- Show upload progress, retry failures, and prevent completion until required uploads synchronize.
- Recover queued photo work after refresh or PWA restart.
- Stage optional PNG/JPEG photographs and their precise finding source-operation dependency together with the finding in one transaction. Quota failures retain the selected files for recovery.
- Make upload operation IDs, associations and checksum immutable; a matching replay returns the same evidence, including after audit completion. Reject changed-content replay and completed-observation changes.
- Finalize staged S3 uploads under organization and audit-row locks; concurrent attempts own separate keys and remove only their own losing objects.
- Display uploaded photographs on the audit and Owner/Deputy review pages.

## 17. Phase 12: temporary access

### 17.1 Invitation model

- Random token stored hashed where practical
- Organization, event/audit-batch scope, issuer, issued time, expiry, revocation
- 24-hour lifetime from issuance
- Volunteer display-name capture
- Resulting temporary principal/session identity
- One shareable invitation may be redeemed by several named volunteers. A stable redemption
  operation UUID returns the same actor on retry; a fresh redemption creates a separate real
  history actor without a permanent membership or local/OIDC credentials.
- Invitation secrets are returned only on creation and held in a `/join#token=...` fragment.
  The browser removes that fragment immediately and exchanges it with CSRF protection for the
  existing JDBC session. Invitation lists and history never include the secret.

### 17.2 Permission boundaries

- Only assigned event/audit operations
- No packing-requirement administration
- No finding resolution
- No unrelated inventory browsing
- Immediate server-side expiry/revocation checks
- An exact event grant covers its return-audit tasks as they are created; an exact batch grant
  covers only that batch. The volunteer dashboard shows a waiting state before event tasks exist.
- Scope checks also apply to direct service calls, both sides of scan moves, finding assets, and
  evidence/reference media, including evidence finalization after object storage writes.
- Out-of-scope scanned codes yield an unknown-code observation without inventory identity.
  Response copies redact unrelated historical destinations/findings while stored facts remain
  immutable.
- The persistent audit outbox checks the fixed grant deadline before recovery, recording,
  synchronization, and completion. Expired/revoked identities stop and preserve unsent rows;
  another redemption uses a new actor partition.

### 17.3 Exit criteria

- QR invitation grants only intended audit access.
- Expired and revoked tokens fail immediately.
- Audit entries identify the temporary volunteer name/session.

## 18. Phase 13: archive, search, operational dashboard, and exports

### 18.1 Archive behavior

- Archive/restore supported entities
- Archive-safe pickers and references
- Explicit archived filters
- No loss of historical labels in event/audit displays
- Zero-balance and completed-event/audit eligibility follows specification section 25. Completed audits use separate archive metadata; user restoration leaves the account disabled until explicitly enabled.

### 18.2 Search

- Public code exact lookup
- Model/asset/container name search
- Category and location filters
- Serial/MAC/custom-string search
- Consumable-model and stock-place search
- Condition, lifecycle, booking, repair, and audit-status filters

### 18.3 Dashboard

- Upcoming events
- Currently checked out
- Pending/blocked audits
- Review required
- In repair
- Metadata incomplete
- Containers incomplete/unavailable
- Consumables at or below the model's low-stock threshold across active on-hand balances

### 18.4 Data exports

- P-touch CSV as already implemented
- Inventory CSV export for backup/reporting
- Consumable balance and movement-ledger CSV exports
- Event manifest download
- Audit result download
- Activity/history export if required
- Reporting exports read every organization-scoped row through bounded queries, preserve exact quantities/history and protect textual spreadsheet cells. They are not a database/media backup replacement.

### 18.5 Exit criteria

- Normal search excludes archived/inactive records by default.
- Operational work queues lead directly to the next required action.
- Exported data accurately reflects organization scope.

## 19. Phase 14: production hardening and first stable release

### 19.1 Security review

- Authorization matrix review for every endpoint
- Cross-organization tests
- Upload hardening
- Session/cookie configuration
- OIDC issuer/redirect configuration, account-linking rules, local Owner recovery, and provider-outage behavior
- CSRF strategy where applicable
- Rate limiting for login, temporary invitations, and code lookup
- Dependency and container scanning
- Secret-handling review

### 19.2 Reliability

- Transaction-bound activity logging
- Concurrency tests for reservations, scan movement, and audit completion
- Database indexes reviewed with realistic data volumes
- Backup/restore rehearsal
- Migration upgrade rehearsal from the prior release candidate
- Media orphan detection/cleanup tool

### 19.3 Browser/device testing

- Current Safari on iPhone/iPad
- Current Chrome on Android
- Desktop Chrome, Edge, Firefox, and Safari where available
- Camera permission denial/recovery
- PWA install/update behavior
- Low-connectivity and reconnect tests
- Multiple camera selection where devices expose it

### 19.4 Deployment deliverables

- Versioned OCI application image produced by Jib and published to GitHub Container Registry
- Immutable semantic-version and commit tags, release-only `latest`, and recorded image digest
- Maintained application + PostgreSQL + SeaweedFS Docker Swarm stack
- Documented external S3-compatible storage configuration that replaces the SeaweedFS service
- Reverse-proxy/TLS example
- SMTP configuration guide
- Upgrade and migration guide
- PostgreSQL and media backup guide
- Restore verification guide
- Initial administrator runbook

### 19.5 Stable-release gate

- All specification acceptance scenarios pass.
- No unresolved critical/high security findings.
- Printed labels and packing sheets are physically verified.
- Backup and restore have been tested on a clean host.
- An event has been rehearsed end-to-end with volunteer-equivalent users.

## 20. Cross-cutting test strategy

### 20.1 Unit tests

- Public-code generation/normalization/checksum
- Requirement matching
- Reservation expansion
- Consumable balance and available-to-promise calculations
- Stock-movement reason and authorization rules
- Lifecycle transitions
- Permission decisions
- Layout fit calculations

### 20.2 PostgreSQL integration tests

- Composite tenant constraints
- Uniqueness rules
- Transactional cycle prevention
- Reservation concurrency
- Immutable completion records
- Idempotent scanner writes
- Archive behavior
- Stock-ledger immutability and concurrent no-negative-balance enforcement
- External-identity uniqueness and verified-email linking rules

Use a real PostgreSQL instance rather than substituting an in-memory database.

### 20.3 End-to-end tests

- Model and bulk asset creation
- Label generation and lookup
- Nested containment
- Booking and conflict flows
- Checkout/check-in
- Bottom-up audit
- Cable swapping
- Damage and review
- Lost-item restoration
- Temporary volunteer access
- Consumable receipt, transfer, booking, issue, return, audit, and consumption
- OIDC login, account linking, disabled-user rejection, and local recovery during provider failure

### 20.4 Visual and physical tests

- Screenshot regression for mobile workflows
- Rendered PDF image comparison
- Physical label alignment calibration
- QR readability from the actual printers and label stock

## 21. Key technical risks and mitigations

| Risk                                                     | Mitigation                                                                                                                    |
| -------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| Mobile-browser camera behavior differs by platform       | Use a proven decoding fallback, test early on physical iOS/Android devices, always provide manual entry.                      |
| Scanner retries create duplicate state                   | Stable client operation IDs and database-enforced idempotency.                                                                |
| Recursive containment or location cycles                 | Transactional ancestor checks plus database constraints for direct self-reference.                                            |
| Dynamic packing changes invalidate reservations          | Central recalculation service, affected-event previews, immutable checkout manifests.                                         |
| Concurrent consumable issues overspend stock             | Lock affected balances transactionally, reject negative results, and test concurrent issue paths against PostgreSQL.          |
| Flexible model quantities hide exact event loss          | Separate container completeness from exact manifest reconciliation.                                                           |
| PDF labels align differently across vendors/printers     | Configurable margins/pitch, calibration pages, physical print tests.                                                          |
| Offline queue conflicts with scans from another device   | Server-authoritative scan ownership, explicit move operation, clear conflict UI.                                              |
| Temporary QR access leaks                                | Narrow scope, 24-hour expiry, revocation, server-side enforcement, rate limiting.                                             |
| OIDC misconfiguration or outage locks out administrators | Keep an enabled local Owner recovery account, validate issuer/redirect settings, and expose actionable health diagnostics.    |
| Email-based OIDC linking joins the wrong account         | Disable it by default; require verified email and one unambiguous match when enabled; persist issuer and subject as identity. |
| S3 deployment behavior diverges                          | One storage contract with shared conformance tests.                                                                           |
| Audit history is accidentally rewritten                  | Immutable completed records and append-only resolutions.                                                                      |

## 22. Milestone summary

### Milestone A: Inventory foundation

Phases 0-4. Users can authenticate locally or through optional OIDC, define catalog data, create coded assets, track consumable balances, upload photos, and organize nested storage.

### Milestone B: Operational containers

Phases 5-6. Containers have packing specifications/templates, real labels, scanner lookup, and printable/P-touch output.

### Milestone C: Reservations and movement

Phases 7-8. Events can reserve equipment, prevent conflicts, check out exact manifests, produce PDFs, and accept returns.

### Milestone D: Audit and remediation

Phase 9.1, then Phases 10 and 11, followed by the quality-of-life inventory refinement, then Phases 9.2 and 9.3, followed by Phase 12. Online bottom-up audits and flexible cable matching precede findings/repairs/seals, packing sheets, and polished inventory workflows; persistent scan and photo recovery now extends those completed workflows.

### Milestone E: Stable self-hosted release

Phases 13-14. Search, dashboards, exports, archiving, hardening, deployment, backup, restore, and production acceptance are complete.

## 23. Immediate next actions

1. Review and approve this specification and mark any deliberately deferred behavior.
2. Treat ADR-0001 as the accepted stack and structure baseline; update it only when a concrete implementation finding requires a change.
3. Treat ADR-0002 as the accepted public-code checksum construction; its test vectors are authoritative for the backend and frontend implementations.
4. Implement the accepted authentication/session design in ADR-0003, including optional OIDC and local Owner recovery.
5. Initialize the Spring Boot and PWA sources, Gradle/Jib integration, GitHub Actions validation and GHCR publication, PostgreSQL/SeaweedFS Swarm deployment, and migration tooling.
6. Implement the organization/user/membership skeleton.
7. Implement categories, models, custom-field definitions, physical assets, consumable stock, and public-code generation as the first demonstrable vertical slice.
