# ADR-0003: Authentication and session design

Status: Accepted

Date: 2026-09-25

## Context

BigContainers is primarily self-hosted by small organizations. It needs a simple local login that works without external infrastructure, while some installations already have a third-party identity provider and should not have to maintain another password for every permanent user.

Authentication must fit the same-origin React/Spring architecture from ADR-0001, preserve server-side organization and role enforcement, support temporary volunteer QR access, and avoid locking an Owner out after an OIDC provider outage or configuration mistake.

OAuth 2.0 alone authorizes access to APIs and does not define a stable login identity. OpenID Connect (OIDC) adds the identity token and issuer/subject semantics needed for login.

## Decision

### Permanent-user methods

Use Spring Security with two optional permanent-user authentication methods:

- Local username/password authentication, enabled by default
- A generic OIDC provider through Spring Security OAuth 2.0 Client

Local passwords use Spring Security's current adaptive `PasswordEncoder` with a versioned encoding identifier so the cost and algorithm can be upgraded. Login attempts are rate-limited and authentication failures do not reveal whether an account exists.

Initial deployment modes are:

- `LOCAL_ONLY`
- `LOCAL_AND_OIDC`

An installation may enforce OIDC for ordinary permanent users, but it must retain at least one enabled local Owner recovery account. Removing or disabling the final usable Owner authentication method is rejected.

### Internal identity and authorization

`User` remains the internal principal. Organization memberships, roles, account-disabled state, and authorization decisions remain in BigContainers.

Add `ExternalIdentity` with:

- Internal UUID
- User ID
- Issuer URI
- Subject
- Last observed email and display claims for diagnostics/profile display
- Created, last-login, and optional unlinked timestamps

The durable external key is the unique pair `(issuer, subject)`. Email is not a durable login identifier because it can change or be reassigned.

Provider groups and claims do not assign BigContainers roles in the initial release. Just-in-time user provisioning is also deferred.

### Linking policy

Owner-approved or preconfigured linking is the safe default.

Automatic email linking is disabled by default. If an Owner enables it, linking succeeds only when:

1. The provider reports `email_verified=true`.
2. Exactly one eligible internal user has the normalized email.
3. The external identity is not already linked.
4. The internal account is enabled.

Ambiguous, missing, or unverified email claims stop with a clear request for Owner assistance. A successful first link is persisted by issuer and subject; later email changes do not change the linked identity.

### Provider configuration

Configure the provider with an issuer URI, client ID, client secret, display name, and scopes. Use issuer discovery and the scopes `openid profile email` by default.

Secrets come from environment variables or mounted secret files and are never committed. The documented callback URI uses the public HTTPS origin. Reverse proxies must pass trusted forwarded scheme and host information.

### Browser session and tokens

Both local and OIDC authentication end in the same Spring Session JDBC-backed application session described by ADR-0001:

- Secure, `HttpOnly`, `SameSite=Lax` cookie
- Session rotation after authentication
- CSRF protection on browser mutations
- No bearer or provider token in `localStorage` or other JavaScript-readable persistent storage

Provider access and refresh tokens, if retained at all, stay server-side and are encrypted or protected with deployment secrets. BigContainers does not require provider API access for initial login, so tokens should not be retained longer than Spring Security needs.

Local logout invalidates the BigContainers session. Provider-wide logout is best effort because not every provider supports a uniform end-session flow.

### Temporary access

Temporary volunteer invitation tokens remain a separate flow. A valid invitation is exchanged for its own narrowly scoped server-side session. Enabling OIDC neither disables nor broadens temporary access.

## Rejected alternatives

### Bare OAuth 2.0 login

Rejected because it does not provide the standard issuer/subject identity contract needed for authentication.

### OIDC-only with no local recovery account

Rejected for the initial self-hosted product because provider downtime, DNS failure, expired client secrets, or a redirect-URI mistake could lock out every administrator.

### Email as the external identity key

Rejected because email addresses are mutable and can be reassigned.

### Provider claims as application authorization

Rejected initially because providers express groups and claims differently, and it would couple core Owner/Deputy/Operator permissions to deployment-specific configuration.

### Browser-held bearer tokens

Rejected because the same-origin deployment already supports hardened server-side sessions and does not need the added exposure and refresh complexity.

## Consequences

- Installations without an identity provider keep a small, ordinary local-login setup.
- Installations with OIDC gain single sign-on without changing the authorization model.
- Account linking requires explicit UI and audit history.
- Deployment documentation must include redirect URI, forwarded-header, secret, provider-outage, and recovery procedures.
- Security tests must cover issuer/subject uniqueness, verified-email linking, ambiguous matches, disabled users, session rotation, CSRF, logout, and local recovery during provider failure.

## Related decisions

- [ADR-0001: Application technology stack](ADR-0001-application-stack.md)
- [ADR-0004: Application container delivery](ADR-0004-container-delivery.md)
