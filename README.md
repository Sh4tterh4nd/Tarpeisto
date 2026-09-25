# BigContainers

BigContainers is a focused equipment, container, booking, checkout, return, and audit system for small event-technology teams.

The project is currently in the specification and implementation-planning stage.

## Documentation

- [Detailed functional specification](docs/SPECIFICATION.md)
- [Implementation plan](docs/IMPLEMENTATION_PLAN.md)

## Intended architecture

- One responsive React and TypeScript web application installable as a PWA
- A Java 25 LTS, Spring Boot 4.1 backend built with the Gradle wrapper
- PostgreSQL 18 as the primary datastore
- S3-compatible object storage for images, using AWS S3 or a self-hosted service such as MinIO
- One production application OCI image: the compiled frontend is served by Spring Boot
- Google Jib builds and publishes the application image to GitHub Container Registry through GitHub Actions
- A simple self-hosted Docker Compose deployment
- No native Android or iOS application requirement

See [ADR-0001](docs/adr/ADR-0001-application-stack.md) for the application stack and [ADR-0004](docs/adr/ADR-0004-container-delivery.md) for the accepted container and CI delivery design.

Development and naming conventions are documented in [Development policies](docs/DEVELOPMENT_POLICIES.md).
