# ADR-0004: Application container delivery

Status: Accepted

Date: 2026-09-25

## Context

BigContainers has one Spring Boot backend and one React PWA. Production should expose one application origin and should not require a Node.js server, a separate frontend container, or a hand-maintained Dockerfile. The project will use GitHub Actions and GitHub Container Registry (GHCR) for continuous integration and image distribution.

"One container" in this decision means one **application container**. PostgreSQL and MinIO, or an external S3-compatible service, remain separate runtime services so that their data, lifecycle, upgrades, and backups are independent from the application image.

## Decision

### Application image

- The React application is built into static assets during the Gradle build and copied into Spring Boot's classpath static resources.
- Spring Boot serves the SPA, the REST API, and authorized media endpoints from one origin.
- The runtime OCI image contains the Java runtime and the packaged Spring Boot application, including the compiled frontend.
- Node.js, pnpm, Vite, and frontend source files are not present in the runtime image.
- No Nginx or other web-server sidecar is required for application delivery. A reverse proxy may still terminate TLS for a self-hosted installation.
- PostgreSQL and object storage are not embedded in the application image.

### Jib build

- The image is built by the Google Jib Gradle plugin, initially pinned to `com.google.cloud.tools.jib` version `3.5.4`.
- Jib configuration lives in the Gradle build and is the authoritative production image definition.
- A Dockerfile is not the production build path.
- The base Java 25 runtime image is explicitly configured and pinned by digest before the first production release.
- The image runs as a non-root user and exposes application port `8080`.
- Jib OCI labels include at least:
  - `org.opencontainers.image.source`
  - `org.opencontainers.image.revision`
  - `org.opencontainers.image.version`
  - `org.opencontainers.image.description`
- The source label points to the GitHub repository so GHCR can associate the package with its source.

### Frontend/Gradle integration

- CI installs the pinned pnpm version and frontend dependencies with the frozen lockfile before invoking the production Gradle build.
- A Gradle task runs the frontend production build.
- `processResources` depends on that task and copies the resulting files into the Spring Boot static-resource output; generated frontend files are not committed.
- The normal production build, `jib`, and `jibBuildTar` therefore cannot create an application artifact without the matching frontend.
- The frontend remains independently runnable through Vite during local development.

### GitHub Actions

Pull-request validation:

1. Check out the repository.
2. Set up the pinned Java and pnpm versions and use dependency caches.
3. Install frontend dependencies with the frozen lockfile.
4. Run frontend format, lint, type, unit-test, and production-build checks.
5. Run backend formatting, tests, and integration checks.
6. Run `jibBuildTar` to prove that the complete production application image can be assembled.
7. Do not authenticate to GHCR or publish images from pull requests, including forks.

Publishing from the default branch and version tags:

1. Repeat all required validation; publication never replaces tests.
2. Grant the publish job only `contents: read` and `packages: write` permissions.
3. Authenticate to `ghcr.io` using `github.actor` and the workflow's `GITHUB_TOKEN` through the Docker login action. Jib reads the resulting Docker credential configuration.
4. Invoke Jib to build and push directly to GHCR; do not add a Docker daemon build/push step.
5. Record the published image digest in the workflow summary or release metadata.

Third-party GitHub Actions must be pinned to full commit SHAs. A personal access token is not required for ordinary publication from this repository.

### Registry and tags

- The canonical image name is `ghcr.io/<lowercase-owner>/bigcontainers`.
- A default-branch build publishes:
  - `main`
  - `sha-<12-character-commit-prefix>`
- A Git tag `vX.Y.Z` publishes:
  - `X.Y.Z`
  - `X.Y`
  - `latest`
  - `sha-<12-character-commit-prefix>`
- Semantic-version and commit tags are immutable. `latest` moves only for a versioned release, not for every default-branch build.
- Production deployments should select an exact semantic version or image digest rather than `main` or `latest`.
- Package visibility is configured in GitHub Packages and is not encoded in the build.

### Self-hosted deployment

- The first-party Compose file references the GHCR application image and allows an explicit image tag or digest override.
- Compose starts the application, PostgreSQL, and MinIO by default. MinIO can be omitted when an external S3-compatible endpoint is configured.
- Runtime secrets and service endpoints are injected through environment variables or mounted secret files; they are never baked into the image.
- Compose health checks use the Spring Boot readiness endpoint. The deployment does not depend on an image-level Docker `HEALTHCHECK` instruction.

## Consequences

- There is one application artifact and one public origin while still keeping stateful infrastructure independently manageable.
- Jib provides daemonless, reproducible, layered image builds and can publish directly from Gradle.
- The release workflow does not need a Dockerfile or Docker daemon, although local Compose still needs a container runtime.
- CI needs both the Java and frontend toolchains to build the single application image.
- The frontend and backend cannot accidentally be released at different versions.
- Base-image upgrades and action-SHA updates become explicit maintenance tasks.
