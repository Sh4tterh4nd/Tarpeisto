# ADR-0005: Durable active-audit outage recovery

Status: Accepted

Date: 2026-09-30

## Decision

An audit must start online. Only its authoritative snapshot, container identity, queued commands and optional finding photographs are retained in Dexie/IndexedDB. Cached inventory browsing and offline completion are excluded. Local persistence precedes every queued request; storage failure is never reported as saved. Pending scans do not count as authoritative matches.

Commands have immutable payloads and stable operation UUIDs. They drain FIFO within the organization/user partition after live session verification. Temporary failures use bounded exponential backoff; terminal failures remain visible with retry/cancel. Logout preserves pending work for the same identity but stops replay, including across tabs. Expected actor headers fence cookie switches on the server. No session or CSRF tokens are persisted.

An atomic IndexedDB lease with a heartbeat and fencing token coordinates tabs. Completion uses that lease and checks the entire persistent audit queue, including failed photographs. Acknowledgement and snapshot updates share one transaction. Photograph bytes and metadata share the enqueue transaction and are removed only after acknowledgement or explicit cancellation. Findings expose their source operation so dependent uploads resolve the precise server finding.

Each snapshot carries a revision. Initial server reads capture the revision before the request and compare it transactionally before caching the reply, so a delayed GET cannot replace a later acknowledgement or completion from another tab. Completed state cannot regress for the same audit attempt.

Cached identity carries a durable generation. Logout and account changes increment it in IndexedDB, creating a disabled tombstone even before the first audit is cached. Cache writes compare the generation captured before their request; stale replies cannot re-enable offline recovery while a cross-tab notification is delayed. A newly verified same-account session can resume retained work using the current generation.

Temporary volunteer identity also carries the invitation's fixed expiry. Recovery, enqueue, synchronization and completion reject expired identity and disable its durable generation. A fresh redemption uses a distinct user partition and cannot resume another volunteer's commands. The server enforces revocation on the next live request; offline clients enforce the known expiry locally. See [ADR-0006](ADR-0006-temporary-volunteer-access.md).

Audit evidence is tenant-owned, append-only metadata attached to one finding and audit. Upload UUID, associations and checksum define immutable replay semantics. Each S3 attempt owns separate object keys; database finalization shares the organization lock used by audit completion. Replay is checked before completed-state rejection. Database constraints protect same-organization associations and completed evidence.

## Consequences

Short outages and reloads preserve active-audit work. Browser storage availability and capacity remain device constraints; users see persistence failures and unsynchronized work. Existing server authorization and idempotency remain authoritative. Explicit cancellation discards local work; server acknowledgements remain immutable.
