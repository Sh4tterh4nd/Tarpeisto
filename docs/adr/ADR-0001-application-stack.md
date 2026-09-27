# ADR-0001: Application technology stack

Status: Accepted

Date: 2026-09-25

## Context

BigContainers is a self-hostable equipment-management application for a small event-technology team. Its most demanding interactions are mobile QR scanning, camera uploads, short-outage resilience, exact transactional booking/audit rules, and deterministic PDF/label output. It does not need public-page SEO, server-side rendering, independent services, or native mobile applications.

The production topology should remain understandable to a small operator: one application, PostgreSQL, and S3-compatible object storage.

## Confirmed decisions

### Backend and build

- Java 25 LTS
- Spring Boot 4.1.1
- Spring MVC rather than WebFlux
- Gradle 9.8.0 through a checked-in Gradle wrapper
- Gradle Kotlin DSL (`build.gradle.kts`)
- Spring Boot dependency management/BOM; transitive framework versions are not pinned individually without a specific reason

The application is primarily transactional CRUD and file transfer. A servlet application with ordinary blocking database and S3 clients is simpler than a reactive stack and is not expected to be a throughput bottleneck.

### Backend source organization

- Conventional layered packages under `io.kellermann.bigcontainers`
- Global `controller`, `service`, `repository`, and `model` packages
- Narrow technical packages such as `config`, `security`, `storage`, `document`, and `exception`
- Dependency direction `controller -> service -> repository/model`
- Transaction and authorization boundaries in services
- No Spring Modulith dependency or enforced business-module split

This application is currently small enough that business modules would add more ceremony than protection. If a layer later becomes difficult to navigate, it may gain feature subpackages or be refactored behind explicit boundaries. That change should respond to measured complexity rather than be built in anticipation of it.

### Database and migrations

- PostgreSQL 18, initially pinned to the current 18.6 container image
- Flyway owns all schema changes
- UUIDs are the internal identifiers
- Organization-aware database constraints implement tenant-safe uniqueness and relationships

The initial data-access recommendation is Spring Data JPA for routine aggregate persistence, with explicit PostgreSQL SQL through Spring's `JdbcClient` for recursive containment/location queries, locking, and reports. Complex invariants must not be hidden in clever ORM cascades. This choice should be validated with a thin proof of concept before the full schema is implemented.

### Object storage

- All production media access uses the S3 API through a small application-owned `MediaStorage` interface.
- AWS SDK for Java 2.x is the initial client.
- AWS S3 and Garage are the initial supported implementations.
- The self-hosted Compose file includes Garage; it can be disabled when an external endpoint is configured.
- Bucket names, endpoint, region, path-style addressing, credentials, and server-side encryption settings are configuration.
- PostgreSQL stores media metadata and opaque object keys, not image bytes or user-supplied filenames.

Maintaining a separate filesystem implementation would create different path, permission, backup, and consistency behavior. A self-hosted S3-compatible server gives self-hosters the same storage contract used in hosted deployments.

MinIO was the originally accepted self-hosted server. It was replaced on 2026-09-25; see [the amendment below](#amendment-2026-09-25-self-hosted-object-store).

## Frontend decision

Use a React single-page application with:

- TypeScript in strict mode
- Vite
- React Router
- Material UI behind BigContainers-owned components and design tokens
- A pnpm workspace with the package-manager version pinned in `package.json`
- `vite-plugin-pwa` backed by Workbox
- Dexie/IndexedDB for the explicit idempotent scan and pending-upload queue
- Generated TypeScript API types/client from the backend OpenAPI document
- Vitest and React Testing Library for component/unit tests
- Playwright for end-to-end tests, including mobile viewports and reconnect scenarios

### Why React

The existing Leirly project already establishes a working React/TypeScript/Vite/pnpm/MUI/OpenAPI/Vitest/Playwright/Workbox toolchain and feature-oriented source conventions. Reusing that experience, dependency knowledge, testing approach, and shared mental model is more valuable than introducing Vue for this project. Vue would offer somewhat more concise component syntax, but it provides no meaningful QR, camera, PWA, offline-storage, or accessibility advantage for BigContainers. BigContainers does not need Next.js or another server-rendering framework.

BigContainers uses one frontend application rather than copying Leirly's organizer/participant split. Owners, deputies, operators, and temporary volunteers use the same installed PWA, and their server-enforced permissions determine available routes and actions. Route-level code splitting can keep volunteer scanner flows small without creating a second deployable application.

## Application integration

### API and authentication

- Versioned REST/JSON API under `/api/v1`
- OpenAPI as the machine-readable API contract
- Spring Security with server-side authorization
- Same-origin secure, `HttpOnly`, `SameSite=Lax` session cookie
- Spring Session JDBC so restarts or a second application instance do not invalidate every login
- CSRF protection remains enabled for browser mutations
- No bearer token is stored in `localStorage`

Temporary volunteer invitation tokens are exchanged for narrowly scoped server-side sessions. The QR token is not reused as the session credential.

Permanent-user authentication supports local credentials and an optional OpenID Connect provider while retaining the same application session and internal authorization model. The detailed identity linking, provider configuration, and local Owner recovery decision is recorded in [ADR-0003](ADR-0003-authentication-and-session.md).

### QR scanning and generation

- Use the browser `BarcodeDetector` only as a feature-detected fast path because support is not universal.
- Provide a maintained JavaScript/WASM QR decoder fallback, initially ZXing-based.
- Generate QR codes on the server with ZXing so labels and PDFs use one deterministic implementation.
- Require HTTPS outside localhost for camera access.

### PDFs and labels

- Generate checkout manifests, A4 labels, and duplicated container sheets on the server.
- Use Apache PDFBox for exact page geometry and embedded fonts.
- Test output by rendering PDFs to images and by physical printer calibration.

### Packaging

- During development, Vite runs separately and proxies `/api` to Spring Boot.
- The production build copies the compiled frontend into Spring Boot static resources.
- Spring Boot serves the SPA fallback, API, and media authorization endpoints from one origin.
- Google Jib builds and publishes one OCI application image directly from Gradle; a production Dockerfile is not required.
- Node.js is used while compiling the frontend but is not present in the runtime image.
- PostgreSQL and S3-compatible object storage remain separate services and are not embedded in the application container.

The detailed build, GitHub Actions, GHCR, tagging, and deployment decision is recorded in [ADR-0004](ADR-0004-container-delivery.md).

## Version policy

The initial pinned baseline on 2026-09-25 is Spring Boot 4.1.1, Java 25 LTS, Gradle 9.8.0, and PostgreSQL 18.6. Dependency versions are always committed in Gradle files, the Gradle wrapper, container references, and the frontend lockfile. "Latest stable" is an update policy, not an unbounded build-time version selector.

- Patch updates should be routine after CI passes.
- Major framework, Java, Gradle, and PostgreSQL upgrades require a small ADR or an update to this ADR.
- PostgreSQL minor releases stay current within the selected major.
- Production container digests may additionally be pinned for reproducible releases.

## Consequences

- Operators run only the application, PostgreSQL, and either Garage or an external S3-compatible service.
- The browser and API share an origin, simplifying sessions, CSRF, PWA scope, and deployment.
- The frontend remains independently testable without becoming a separately deployed production service.
- Media backup must cover the object store as well as PostgreSQL.
- Offline behavior remains deliberately narrow: queued scanner mutations and uploads, not an offline replica of the database.

## Amendment 2026-09-25: self-hosted object store

The originally accepted self-hosted object store was MinIO. It is replaced by
[Garage](https://garagehq.deuxfleurs.fr/) for the first-party Compose deployment.

### Reason

MinIO's container images are no longer available to anonymous pulls, so a clean checkout could not
satisfy the specification's requirement that BigContainers be straightforward to self-host.
Verified on 2026-09-25:

- `docker.io/minio/minio` returns `denied: requested access to the resource is denied` for every
  tag, for both the Docker client and a direct registry token exchange.
- `quay.io/minio/minio` is also unavailable anonymously. Quay's public repository API returns `401`
  for it while returning `200` with `is_public: true` for `prometheus/prometheus` and `coreos/etcd`
  by the same unauthenticated method, and the Docker client reports `no such manifest` there for
  both `latest` and a real `RELEASE.*` tag.
- The last MinIO community release on GitHub is `RELEASE.2025-10-15`.

An unmaintained mirror such as `bitnamilegacy/minio` was rejected: a frozen image that will never
receive a security update is not an acceptable default for a self-hosted deployment that stores
user media.

### Scope of the change

This amendment changes only which S3-compatible server the first-party Compose file starts, the
Compose/operator documentation, and the object-store integration tests. It does not change any
application code or architecture:

- Media access still goes through the application-owned `MediaStorage` interface.
- The AWS SDK for Java 2.x remains the client.
- AWS S3 remains a supported production target, and any S3-compatible endpoint remains
  configurable.

Garage requires a mounted configuration file and a one-time cluster-layout, bucket, and access-key
initialization that MinIO did not, so the self-hosting documentation carries an explicit first-run
procedure.

### Consequences

- Self-hosted first start works from a clean checkout without registry credentials.
- Operator documentation is longer, because Garage's initialization is not implicit.
- Object-store conformance tests must run against Garage rather than MinIO, keeping the single
  storage contract that this ADR requires.

## Amendment 2026-09-27: Swarm self-hosted object store

Garage is replaced by [SeaweedFS](https://github.com/seaweedfs/seaweedfs) `weed mini` in the
first-party self-hosted deployment. This supersedes the 2026-09-25 Garage amendment for the current
deployment, while preserving it as the historical reason MinIO is not used.

### Reason

The intended production target is Docker Swarm, not local Docker Compose. SeaweedFS `weed mini`
provides a single-node S3 endpoint and creates the configured bucket on startup, avoiding Garage's
separate cluster layout, bucket, and access-key bootstrap. This keeps the self-hosted path usable
without a committed `.env` file or plaintext credentials in stack YAML.

### Scope of the change

- The first-party `docker-compose.yml` is a Swarm-compatible stack with application, PostgreSQL,
  and `chrislusf/seaweedfs:4.47`.
- PostgreSQL and SeaweedFS use externally created Swarm secrets. Spring imports its secret mounts
  as a configuration tree; SeaweedFS reads its S3 credentials from secret files at startup.
- The two stateful services are one replica each and constrained to a labeled node because their
  default named volumes are local, not highly available.
- AWS S3 and other S3-compatible endpoints remain supported. The application storage interface and
  S3 client remain unchanged.

### Consequences

- A new installation needs only external Swarm secrets and a data-node label; no manual bucket or
  cluster-layout initialization is required.
- Operators must back up PostgreSQL and the SeaweedFS bucket and must not scale the local-volume
  services beyond one replica without shared storage.
- Object-store conformance tests exercise the same S3 contract and need no application behavior
  change solely because the bundled server changed.
