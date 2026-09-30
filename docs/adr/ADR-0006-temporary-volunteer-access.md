# ADR-0006: Temporary volunteer audit access

Status: Accepted

Date: 2026-09-30

## Context

Occasional volunteers need attributable audit access without permanent accounts or access to the organization's inventory. An invitation may be printed before an event's return audits exist, and several volunteers may use the same invitation. Active audit work can survive short outages under ADR-0005, so temporary identity and expiry must also govern recovery and replay.

## Decision

An Owner or Deputy issues an invitation for exactly one event booking or audit batch. Event scope includes that booking's return audits, including batches created later. Batch scope includes only that batch. Both the invitation and every resulting session expire at the original issuance time plus 24 hours; redemption, activity and session renewal never extend that deadline. Revocation rejects subsequent operations from every session derived from the invitation.

Store only the SHA-256 digest of an independently generated, 32-byte random invitation token. Return the secret once on creation; invitation listings contain metadata only. Put the secret in the join URL fragment, capture it in memory and remove it from the address before application requests. Do not persist it in browser storage. Rate-limit successful and unsuccessful redemption attempts.

Each named redemption creates a distinct internal user without local credentials, external identities or permanent organization membership. A tenant-owned volunteer-session record links that actor to its invitation and fixed expiry. Existing immutable audit history therefore retains real user attribution. Retrying the same redemption operation preserves its identity; a fresh redemption gets a new identity even when the name is unchanged.

The temporary principal explicitly identifies temporary access independently of the four permanent membership roles. It may list, read, start and complete assigned audits, scan and undo, move observations between assigned audits, record findings and consumable observations, and attach evidence. It may not launch standalone audits, check equipment out or in, browse inventory, administer packing requirements, resolve findings or administer users.

Authorization is authoritative on every API request and at service entry points. Audit mutations recheck scope, expiry and revocation after obtaining the organization lock shared by revocation. Evidence upload checks before storage transfer and again during finalization; a losing upload removes only its own staging objects. Both ends of correction operations must be assigned.

Audit-specific container projections and scoped media access supply only what the assigned workflow needs. An asset code outside the permitted set is handled as an unknown code without disclosing asset identity or details. Responses redact out-of-scope destinations and suggestions in previously stored audit snapshots without altering immutable history. Media original and thumbnail requests enforce the same scope as media listings.

The existing organization/user outbox partition isolates each volunteer. Cached identity includes the fixed expiry. Recovery, enqueue, replay and completion stop at that deadline, including offline and across tabs. Logout retains queued work but disables replay; a new redemption cannot replay an earlier volunteer's partition. Revocation during an outage takes effect on the next live server request.

## Consequences

Temporary volunteers have a small assigned-audit workspace and retain access only for the invitation's fixed window. Permanent account listings exclude ephemeral actors, while audit and activity history preserve their names and identities. Database constraints enforce tenant ownership and exactly one invitation scope. Tests cover direct service calls as well as HTTP authorization, revocation races, media boundaries and offline identity recovery.

## Related decisions

- [ADR-0003: Authentication and session design](ADR-0003-authentication-and-session.md)
- [ADR-0005: Durable active-audit outage recovery](ADR-0005-audit-outage-recovery.md)
