# BigContainers Development Policies

Status: Initial mandatory policy

Derived from: useful conventions in `C:\Users\Arieh\IdeaProjects\Leirly`, simplified for BigContainers

## 1. Purpose and precedence

These rules define how BigContainers source code is organized and named. They apply to human and automated contributors.

Read the project documents in this order before implementation:

1. `docs/SPECIFICATION.md` -- required product behavior
2. `docs/adr/` -- accepted architecture decisions
3. `docs/DEVELOPMENT_POLICIES.md` -- implementation rules
4. `docs/IMPLEMENTATION_PLAN.md` -- delivery order and acceptance criteria

Resolve documentation conflicts before writing code. The words **must** and **must not** are mandatory. A **should** rule may be departed from only for a concrete reason recorded in the relevant change.

## 2. Repository layout

```text
BigContainers/
|-- backend/                   # one Gradle/Spring Boot project
|-- frontend/
|   |-- apps/
|   |   `-- web/               # one React PWA
|   `-- packages/
|       |-- api-client/
|       `-- shared-ui/
|-- infrastructure/
|   |-- compose/
|   `-- container/
`-- docs/
    `-- adr/
```

- The backend is one Gradle project and one deployable Spring Boot application.
- Spring Modulith is not part of the baseline.
- The frontend is a pnpm workspace with the package-manager version pinned in `package.json`.
- BigContainers has one frontend app. Permissions and route-level code splitting distinguish owner/deputy and volunteer experiences.
- Create another frontend package only after code is genuinely shared or generated.
- Do not create empty packages or directories for hypothetical future code.
- Generic dumping grounds such as `common`, `misc`, or `util` are forbidden. A narrowly named helper close to its caller is preferred.
- Text files use UTF-8 without a byte-order mark, LF endings, no trailing whitespace, and one final newline.
- Java and Kotlin use four spaces; TypeScript, JavaScript, JSON, YAML, and CSS use two spaces.

## 3. Backend package structure

### 3.1 Base package

The Java base package is `io.kellermann.bigcontainers`. The entry point is:

```text
io.kellermann.bigcontainers.BigContainersApplication
```

Production code starts with this structure:

```text
backend/src/main/java/io/kellermann/bigcontainers/
|-- BigContainersApplication.java
|-- config/
|-- controller/
|-- service/
|-- repository/
|-- model/
|-- security/
|-- storage/
|-- document/
`-- exception/
```

Packages are created only when their first real type is added.

### 3.2 Package responsibilities

| Package | Responsibility |
|---|---|
| `controller` | Spring MVC controllers and their request/response records |
| `service` | Business workflows, transaction boundaries, authorization-sensitive operations, and domain coordination |
| `repository` | Spring Data repositories, `JdbcClient` queries, persistence projections, and custom repository implementations |
| `model` | JPA entities, enums, value objects, and business state |
| `config` | Application-wide Spring configuration and validated configuration properties |
| `security` | Authentication, authorization, session handling, and security adapters |
| `storage` | S3-compatible storage interfaces and implementations |
| `document` | PDF, QR, label, and CSV generation |
| `exception` | Stable application exceptions and RFC 9457 Problem Details mapping |

Dependency direction is:

```text
controller -> service -> repository
                   \-> model
                   \-> storage/document when required
```

Rules:

- Controllers must not call repositories directly.
- Repositories must not depend on controllers or services.
- Models must not depend on controllers, HTTP DTOs, or S3 clients.
- Services may call other services directly when the workflow genuinely crosses concerns. Do not introduce ports/events solely to avoid an ordinary method call.
- Cyclic service dependencies are a design problem and must be removed.
- Spring application events are used only for a real asynchronous or decoupled side effect, not as a default communication mechanism.
- JPA entities are never used as HTTP request or response types.
- Do not create parallel domain and persistence models unless a concrete mismatch makes the duplication worthwhile.

### 3.3 Growth rule

The global layer packages are intentionally simple. If one becomes difficult to navigate, split that layer by feature without changing the overall dependency direction, for example:

```text
controller/inventory/
service/inventory/
repository/inventory/
model/inventory/
```

Do this in response to actual size or coupling, not before implementation. Introducing Spring Modulith or separately enforced business modules later requires an ADR explaining the problem being solved.

## 4. Java naming rules

### 4.1 Product terminology

- Use `AssetModel`, never the ambiguous type name `Model`.
- Use `Asset` for one physical unit. A container remains an asset whose model is container-capable.
- Use `AssetCode` for the six-character checked public identifier. A UUID is named `id`/`assetId`, never `code`.
- Use `Booking` or `EventBooking` for the equipment reservation/event record.
- Reserve a `*Event` suffix for completed facts such as `BookingCheckedOut` if application events are later needed.
- Use `ContainerAudit` or `AuditTask` for equipment audits. Use `ActivityLog` for operator/security history.
- Use `PackingRequirement` with explicit variants such as `ExactAssetRequirement` and `ModelQuantityRequirement`.
- Use qualified identifiers such as `organizationId`, `assetId`, `modelId`, `containerId`, and `bookingId`.
- Treat initialisms as words in Java names: `QrCode`, `Url`, `Id`, and `S3MediaStorage`.

Names must describe responsibility. Avoid `Manager`, `Processor`, `Handler`, `Helper`, `Util`, `Data`, and `Info` when a domain-specific name is possible.

### 4.2 Conventional suffixes

| Suffix | Meaning | Example |
|---|---|---|
| `Controller` | Spring MVC boundary | `AssetController` |
| `Service` | Business workflow/use case | `BookingService`, `AuditService` |
| `Repository` | Persistence access | `AssetRepository`, `JdbcAssetSearchRepository` |
| `Request` | HTTP request record | `CreateAssetRequest` |
| `Response` | HTTP response record | `AssetResponse` |
| `Outcome` | Closed business-operation result | `AuditCompletionOutcome` |
| `View` | Service/query projection | `BookingAvailabilityView` |
| `Snapshot` | Immutable point-in-time data | `CheckoutManifestSnapshot` |
| `Properties` | Typed external configuration | `ObjectStorageProperties` |
| `Configuration` | Spring bean wiring | `ObjectStorageConfiguration` |

Additional rules:

- JPA entities keep the domain name (`Asset`, `Booking`), not `AssetEntity` or `BookingJpaEntity`.
- Request names begin with an action: `CreateAssetRequest`, `MoveAssetRequest`, `CompleteAuditRequest`.
- Responses name the resource: `AssetResponse`, `AssetListResponse`.
- Boolean names are positive: `sealable`, `archived`, `requiresExactAsset`.
- Closed business states use enums or sealed hierarchies rather than combinations of booleans.
- Collections use plural names and return empty collections, never `null`.

### 4.3 Java style

- Target Java 25 without preview features.
- Prefer immutable values and records for request/response types and small value objects.
- Prefer sealed interfaces/classes for closed outcome or requirement variants.
- Use constructor injection. Field injection is forbidden.
- Lombok is not part of the baseline.
- Prefer composition over inheritance.
- Wildcard imports and static mutable state are forbidden.
- `Optional` is primarily a return type, not an entity field, request field, or method parameter.
- Business logic affected by current time receives a `Clock`; it does not call the system clock directly.
- Spotless with pinned formatting versions is authoritative.

## 5. Spring and persistence rules

### 5.1 Controllers and services

- Controllers validate transport syntax, map values, invoke a service, and map the result.
- Controllers contain no transactions, JPA relationship traversal, packing/audit algorithms, or authorization shortcuts.
- Services define transaction boundaries and enforce business authorization.
- Transactions are as short as practical.
- Network calls, email, and S3 operations should not run inside database transactions.
- Spring Security denies access by default; public endpoints are explicitly allowlisted.
- External configuration uses validated `@ConfigurationProperties`.

### 5.2 JPA, JDBC, and PostgreSQL

- Spring Data JPA handles entity persistence and ordinary transactional updates.
- Spring `JdbcClient` handles recursive location/containment queries, availability/reporting projections, bulk operations, and SQL clearer than ORM expressions.
- Open Session in View is disabled.
- Relationships are lazy by default; `EAGER` is not a general query fix.
- API list endpoints use bounded queries/projections rather than loading large object graphs.
- Mutable records use optimistic versioning where concurrent edits matter.
- Important invariants are backed by PostgreSQL constraints as well as service validation.
- Tenant-owned tables include `organization_id` and organization-aware unique and foreign-key constraints.
- Database identifiers use singular `snake_case`: `asset_model`, `packing_requirement`, `audit_task`.
- Instants use PostgreSQL `timestamptz` and Java `Instant`.
- JSONB is reserved for genuinely document-shaped data, not ordinary relationships or searchable fields.
- Flyway is the only production schema-change mechanism. Applied migrations are immutable.
- Integration tests use PostgreSQL, never H2 as a substitute.

### 5.3 REST and OpenAPI

- The API is versioned under `/api/v1` and uses JSON plus RFC 9457 Problem Details.
- OpenAPI is the machine-readable contract.
- Frontend API types are generated; handwritten duplicates of generated request/response shapes are forbidden.
- IDs are serialized as opaque strings.
- Retried commands use an idempotency key or mutation identifier.
- Concurrent mutable operations use expected versions or ETags and return `409 Conflict` for stale writes.
- Potentially unbounded collections use cursor pagination.
- Authorization failures and missing records do not reveal another organization's data.

## 6. Frontend structure and naming

### 6.1 Accepted stack

- React with function components and hooks
- TypeScript strict mode
- Vite
- React Router
- Material UI behind BigContainers-owned wrappers and design tokens
- pnpm workspaces
- Generated OpenAPI types/client using `openapi-typescript` and `openapi-fetch` or an accepted equivalent
- Vitest, React Testing Library, and Playwright
- `vite-plugin-pwa`/Workbox
- Dexie/IndexedDB for the short-outage scan and pending-upload queue

Dependency versions are exactly pinned and committed in the lockfile. Do not use floating ranges.

Vue is not selected. It could offer somewhat terser components, but it offers no material advantage for QR scanning, camera access, PWA installation, IndexedDB, or this application's forms. Reusing the React stack and experience from Leirly has greater value.

### 6.2 Source layout

```text
frontend/apps/web/src/
|-- features/
|   |-- inventory/
|   |-- bookings/
|   |-- audits/
|   |-- scanner/
|   `-- identity/
|-- data/
|   |-- indexeddb/
|   |-- outbox/
|   `-- sync/
|-- platform/
|   |-- capabilities/
|   `-- web/
|-- routes/
|-- config/
`-- test/
```

- Organize application code by product feature, not global frontend `components`, `hooks`, or `services` folders.
- Feature directories use lowercase kebab-case when multiple words are required.
- Components/files use PascalCase: `AssetDetailPage.tsx`, `AuditScanner.tsx`.
- Hooks use `use` plus PascalCase: `useAuditQueue.ts`.
- Pure utilities/adapters use camelCase: `normalizeAssetCode.ts`, `auditApi.ts`.
- Tests are colocated as `*.test.ts` or `*.test.tsx`.
- Page components end in `Page`; modal forms end in `Dialog`; status components may end in `Chip`.
- Feature API wrappers stay in their feature. The shared `api-client` package owns generated code and cross-cutting authentication/error behavior.
- `shared-ui` owns design tokens, theme construction, and reusable MUI wrappers.
- Keep transient UI state local. Server state, offline data, and UI state remain separate.
- Components never access IndexedDB directly; the data/outbox layer owns schema upgrades, retry state, and organization/session partitioning.
- QR scanning, camera, connectivity, and installation use capability interfaces with web implementations.
- Do not add Redux, Zustand, or another global state framework until demonstrated shared-state needs justify it.

### 6.3 Accessibility and scanner behavior

- Target WCAG 2.2 AA.
- Actions are keyboard accessible, labeled, and understandable without color alone.
- Mobile controls normally provide at least a 44 by 44 CSS-pixel target.
- Dialogs and validation errors manage focus.
- Scanner flows expose camera-denied, unsupported, unreadable-code, duplicate-scan, offline, and reconnect states.
- `BarcodeDetector` is only a feature-detected fast path; a fallback decoder is required.
- Manual code entry is always available and uses the same normalization/checksum validation as scanned input.

## 7. Test naming and placement

Backend tests mirror the package they exercise:

```text
backend/src/test/java/io/kellermann/bigcontainers/
|-- controller/AssetControllerIntegrationTests.java
|-- service/AuditServiceTests.java
|-- repository/AssetRepositoryIntegrationTests.java
`-- model/AssetCodeTests.java
```

- Pure tests end in `Tests` and do not start Spring.
- PostgreSQL, Spring MVC, security, JPA, S3, or transaction tests end in `IntegrationTests`.
- Testcontainers supplies PostgreSQL and MinIO.
- Time-sensitive tests use a fixed/controlled `Clock`.
- Randomized tests use reproducible seeds where failure diagnosis requires them.
- Playwright covers owner/deputy and temporary-volunteer journeys, manual scanner fallback, and short connectivity interruptions.

## 8. Enforcement and evolution

At minimum, CI runs the applicable forms of:

```text
backend/gradlew spotlessCheck check
pnpm format:check
pnpm lint
pnpm typecheck
pnpm test
pnpm build
pnpm test:e2e
```

The build should enforce formatting, no wildcard Java imports, OpenAPI/client drift detection, dependency locking, and frontend boundary lint rules.

Create or update an ADR when changing the base package, overall package architecture, frontend framework, persistence strategy, authentication model, public-code format, offline queue semantics, or deployable topology.

## 9. Container delivery and CI

- Google Jib is the only authoritative production application-image build. Do not add a parallel Dockerfile build unless an ADR replaces this decision.
- Jib is configured in Gradle and its plugin version is pinned.
- A production image build must depend on the frontend production build through Gradle resource processing.
- Generated frontend distribution files must not be committed.
- The runtime image contains the Java runtime and packaged Spring Boot application, including frontend assets; it must not contain Node.js, pnpm, or frontend sources.
- The application process runs as a non-root user.
- The configured base image is pinned by digest for releases.
- PostgreSQL and MinIO are separate services and must not be installed in the application image.
- GitHub Actions validates pull requests with `jibBuildTar` but never publishes from pull requests or forks.
- Publishing uses `GITHUB_TOKEN`, `contents: read`, and `packages: write`; no long-lived registry password or personal access token is stored for ordinary GHCR publication.
- Third-party actions are pinned by full commit SHA.
- `main`, semantic-version, and commit-derived tags follow ADR-0004. Semantic-version and commit tags must not be overwritten.
- Production documentation recommends an exact semantic version or digest, never a mutable branch tag.
