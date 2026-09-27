# Tarpeisto contributor instructions

Before changing application code, read these documents in order:

1. [`docs/SPECIFICATION.md`](docs/SPECIFICATION.md)
2. The relevant accepted record in [`docs/adr/`](docs/adr/)
3. [`docs/DEVELOPMENT_POLICIES.md`](docs/DEVELOPMENT_POLICIES.md)
4. The relevant phase and acceptance criteria in [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md)

The specification defines behavior, accepted ADRs define architecture, and the development policies define mandatory package and naming conventions. Resolve conflicts in the documents before writing code.

Implementation must preserve the documented controller-service-repository dependency direction under `io.kellermann.tarpeisto`, organization ownership on every tenant-owned record, server-side authorization, idempotent scanner mutations, and immutable completed manifests/audit observations.

Use the global `controller`, `service`, `repository`, and `model` packages defined in the development policies. Do not add empty packages or generic dumping grounds such as `common`, `misc`, or `util` in anticipation of future code.

Run the affected backend and frontend checks described in `docs/DEVELOPMENT_POLICIES.md` and review the complete diff before considering a change complete.
