# BigContainers self-hosted Compose stack

This directory contains the first-party Compose deployment for a self-hosted
BigContainers installation: the application, PostgreSQL, and (optionally)
Garage. See [ADR-0004](../../docs/adr/ADR-0004-container-delivery.md) for the
delivery design this stack implements.

All commands below assume they are run from the repository root unless noted
otherwise.

## Why Garage, not MinIO

MinIO was the original choice recorded in ADR-0001. It was replaced with
[Garage](https://garagehq.deuxfleurs.fr/) (`dxflrs/garage`) because MinIO's
container images are no longer anonymously pullable from Docker Hub or
Quay.io (both now require registry credentials, as part of MinIO's
commercial "AIStor" relicensing). See the ADR-0001/ADR-0004 amendments for
the full reasoning; this file only documents how to operate Garage.

Garage is meaningfully more hands-on to bring up than MinIO was: it needs an
explicit one-time cluster layout step before it will serve traffic (see
below). This is normal for Garage, not a sign something is broken.

## First start

1. Copy the environment template and edit it:

   ```bash
   cp infrastructure/compose/.env.example infrastructure/compose/.env
   ```

   At minimum, set real values for `POSTGRES_PASSWORD`,
   `GARAGE_RPC_SECRET` (`openssl rand -hex 32`), `GARAGE_ADMIN_TOKEN`
   (`openssl rand -base64 32`), and `BIGCONTAINERS_APPLICATION_PUBLIC_BASE_URL`.
   `BIGCONTAINERS_S3_ACCESS_KEY` / `BIGCONTAINERS_S3_SECRET_KEY` cannot be
   filled in yet - you get their real values from "Creating the Garage bucket
   and access key" below, after Garage's first start. Leave the placeholders
   in place for now.

   **Also set `BIGCONTAINERS_SEED_OWNER_USERNAME` and
   `BIGCONTAINERS_SEED_OWNER_PASSWORD` now.** No Owner account is created
   unless both are set before the application's first start - without one,
   there is no way to log in at all. See "Authentication and first Owner
   login" below.

2. Start PostgreSQL and Garage first (Garage is included by default; see
   "External S3 / disabling Garage" below to omit it):

   ```bash
   docker compose -f infrastructure/compose/docker-compose.yml \
     --env-file infrastructure/compose/.env up -d postgres garage
   ```

3. Complete the one-time Garage cluster layout and bucket/key setup below.
   Update `.env` with the real access key/secret key it gives you.

4. Start the application:

   ```bash
   docker compose -f infrastructure/compose/docker-compose.yml \
     --env-file infrastructure/compose/.env up -d
   ```

5. Watch the application become healthy:

   ```bash
   docker compose -f infrastructure/compose/docker-compose.yml \
     --env-file infrastructure/compose/.env ps
   ```

   The `app` service reports healthy once
   `http://localhost:8080/actuator/health/readiness` returns success. On
   first start, the application runs its Flyway migrations before it becomes
   ready — see "Migrations and first-run seeding" below.

6. Open `http://localhost:8080` (or your configured
   `BIGCONTAINERS_APPLICATION_PUBLIC_BASE_URL` through a reverse proxy).

## Authentication and first Owner login

### First Owner bootstrap

No Owner account exists until you tell the application to create one.
Set both `BIGCONTAINERS_SEED_OWNER_USERNAME` and
`BIGCONTAINERS_SEED_OWNER_PASSWORD` in your `.env` *before* the application's
first start - if either is blank (the default), no Owner is seeded and there
is no way to sign in. `BIGCONTAINERS_SEED_OWNER_DISPLAY_NAME` (default
`Owner`) and `BIGCONTAINERS_SEED_OWNER_EMAIL` (optional) only affect the
profile shown for that account, not whether it is created.

`BIGCONTAINERS_SEED_OWNER_PASSWORD` is a real secret: never commit a value
for it, and the application does not log it.

### Authentication mode

`BIGCONTAINERS_AUTHENTICATION_MODE` is `LOCAL_ONLY` (default) or
`LOCAL_AND_OIDC`. Selecting `LOCAL_AND_OIDC` without a usable OIDC provider
configuration (`BIGCONTAINERS_OIDC_ISSUER_URI`, `_CLIENT_ID`, and
`_CLIENT_SECRET` all set) makes the application refuse to start - it will
not come up half-configured.

Per [ADR-0003](../../docs/adr/ADR-0003-authentication-and-session.md),
Owner-approved/preconfigured linking of an external identity to an existing
local account is the safe default. `BIGCONTAINERS_AUTHENTICATION_AUTO_LINK_BY_EMAIL`
(default `false`) can enable *automatic* linking instead, but only ever
succeeds when the provider reports `email_verified=true` **and** exactly one
enabled internal user has that normalized email; anything ambiguous,
missing, or unverified always stops and requires Owner assistance regardless
of this setting. Leave it `false` unless you specifically want that
automatic behavior.

### OIDC does not gate local sign-in

OIDC discovery is lazy by design: the application does not contact the
issuer at startup, so a misconfigured or unreachable OIDC provider does
**not** prevent the application from starting and does **not** block local
sign-in. This was verified directly against a real deployment: with the
configured provider unreachable, the application still started normally,
`/oauth2/authorization/oidc` returned a `503` problem document stating that
local sign-in remains available, and a local Owner account signed in without
issue. This is the anti-lockout guarantee ADR-0003 requires - keep at least
one enabled local Owner account regardless of whether OIDC is enabled, so a
provider outage, DNS failure, or expired client secret never locks every
administrator out.

### Login rate limiting

`BIGCONTAINERS_SECURITY_LOGIN_RATE_LIMIT_MAX_ATTEMPTS` (default `5`) and
`BIGCONTAINERS_SECURITY_LOGIN_RATE_LIMIT_WINDOW` (an ISO-8601 duration,
default `PT15M`) bound login attempts per account/window.

The rate limiter is **in-memory and per application instance** - it does not
coordinate across multiple instances behind a load balancer. If you run more
than one `app` instance, each instance enforces its own independent limit
rather than a shared cluster-wide one. This is a deliberate small-deployment
tradeoff, not a bug; it matches the single-instance topology this Compose
file starts by default.

## Creating the Garage cluster layout, bucket, and access key

Garage refuses to serve S3 traffic until it has been told about its own
storage layout, even for a single node. This is a one-time step after the
`garage` container's first start (not needed again on restarts, only after
recreating its data volume). All of the following steps were verified
end-to-end against a real `dxflrs/garage:v2.4.1` container.

1. Confirm the node is up (it will show `NO ROLE ASSIGNED` — that's
   expected before layout is applied):

   ```bash
   docker compose -f infrastructure/compose/docker-compose.yml \
     --env-file infrastructure/compose/.env exec garage /garage status
   ```

2. Read the node's own ID from the first line of `garage node id` (the
   fuller output also explains inter-node connection, which is not relevant
   for a single-node deployment):

   ```bash
   NODE_ID=$(docker compose -f infrastructure/compose/docker-compose.yml \
     --env-file infrastructure/compose/.env exec -T garage /garage node id 2>/dev/null | head -1)
   echo "$NODE_ID"
   ```

3. Assign it a zone and capacity, and apply the layout. The capacity value
   is a soft accounting figure (how much disk Garage assumes it can use); it
   is not enforced as a hard quota, so pick a reasonable estimate of what the
   underlying volume actually has available:

   ```bash
   docker compose -f infrastructure/compose/docker-compose.yml \
     --env-file infrastructure/compose/.env exec garage \
     /garage layout assign -z dc1 -c 100G "$NODE_ID"

   docker compose -f infrastructure/compose/docker-compose.yml \
     --env-file infrastructure/compose/.env exec garage \
     /garage layout apply --version 1
   ```

   `--version 1` is always correct here: it is the layout format version
   (this is the *first* layout ever applied to this cluster), not something
   you need to look up.

4. Confirm the cluster reports healthy (this is also what the Compose
   healthcheck polls):

   ```bash
   docker compose -f infrastructure/compose/docker-compose.yml \
     --env-file infrastructure/compose/.env exec garage /garage health
   ```

5. Create the bucket BigContainers will use (match
   `BIGCONTAINERS_S3_BUCKET`, default `bigcontainers`):

   ```bash
   docker compose -f infrastructure/compose/docker-compose.yml \
     --env-file infrastructure/compose/.env exec garage \
     /garage bucket create bigcontainers
   ```

6. Create an access key for the application:

   ```bash
   docker compose -f infrastructure/compose/docker-compose.yml \
     --env-file infrastructure/compose/.env exec garage \
     /garage key create bigcontainers-app
   ```

   This prints a `Key ID` and `Secret key` - copy both into your `.env` as
   `BIGCONTAINERS_S3_ACCESS_KEY` and `BIGCONTAINERS_S3_SECRET_KEY`
   respectively. This is the only time the secret key is shown in full.

7. Grant that key read/write access to the bucket:

   ```bash
   docker compose -f infrastructure/compose/docker-compose.yml \
     --env-file infrastructure/compose/.env exec garage \
     /garage bucket allow --read --write bigcontainers --key bigcontainers-app
   ```

8. Confirm the grant:

   ```bash
   docker compose -f infrastructure/compose/docker-compose.yml \
     --env-file infrastructure/compose/.env exec garage \
     /garage bucket info bigcontainers
   ```

   You should see `bigcontainers-app` listed with `RW` permissions. Now
   restart (or start) `app` so it picks up the real
   `BIGCONTAINERS_S3_ACCESS_KEY` / `BIGCONTAINERS_S3_SECRET_KEY` from your
   updated `.env`.

Note: the `dxflrs/garage` image is built `FROM scratch` and has no shell -
`docker compose exec garage sh` will not work. Always invoke `/garage`
directly as shown above.

## External S3 / disabling Garage entirely

Garage is gated behind the Compose profile `garage`. `.env.example` sets
`COMPOSE_PROFILES=garage`, so a fresh `docker compose up` starts it by
default. To use an external S3-compatible endpoint (AWS S3 or another
provider) instead:

1. In your `.env`, delete the `COMPOSE_PROFILES` line or set it to an empty
   value. This stops the `garage` service (and its volume) from being
   created at all — `docker compose up` without the profile active simply
   does not start it. The application's `depends_on` entry for `garage` is
   marked `required: false`, so `app` starts normally without it.
2. Set `BIGCONTAINERS_S3_ENDPOINT` to the external endpoint (leave it unset
   entirely for real AWS S3, where the AWS SDK resolves the regional
   endpoint itself).
3. Set `BIGCONTAINERS_S3_REGION`, `BIGCONTAINERS_S3_BUCKET`,
   `BIGCONTAINERS_S3_ACCESS_KEY`, and `BIGCONTAINERS_S3_SECRET_KEY` to the
   values issued by that provider.
4. Set `BIGCONTAINERS_S3_PATH_STYLE_ACCESS=false` for AWS S3. Most
   self-hosted S3-compatible services (Garage included) need it left at
   `true`.
5. Re-run `docker compose ... up -d`. No `garage` container or volume is
   created.

## Pinning the application image

`BIGCONTAINERS_IMAGE` is the single variable that controls the application
image, and it accepts either a tag or a digest:

```bash
# Exact semantic version (recommended for production)
BIGCONTAINERS_IMAGE=ghcr.io/sh4tterh4nd/bigcontainers:1.4.2

# Digest (strongest reproducibility guarantee)
BIGCONTAINERS_IMAGE=ghcr.io/sh4tterh4nd/bigcontainers@sha256:<digest>
```

Do not run production on `:main` or `:latest` — both are mutable and can
change under you between restarts. Find the digest for a given release in
the GitHub Actions publish workflow summary, or with:

```bash
docker buildx imagetools inspect ghcr.io/sh4tterh4nd/bigcontainers:1.4.2
```

The same applies to the bundled Garage image: `docker-compose.yml` pins
`dxflrs/garage:v2.4.1` and carries a comment with its manifest-list digest
(`sha256:9c96caa2612d3411acc5b0e6701fb238dbfba33e533a6d7d3d811a4b12d0d020`,
confirmed via both the Docker Hub API and a real `docker pull` +
`docker inspect`) for operators who want to pin Garage by digest too.

## Migrations and first-run seeding

Flyway runs inside the Spring Boot application at startup (not as a
separate CI/CD step): on every `app` container start, the application
applies any pending migrations before it reports ready on
`/actuator/health/readiness`. This is the "documented, explicit deployment
step" required by the specification — the explicit step is starting (or
restarting) the `app` container against the target database, which is the
only way migrations run.

Practical consequences:

- **Upgrade order**: always upgrade the `app` image (via `BIGCONTAINERS_IMAGE`)
  and restart `app` against the existing `postgres` volume; do not skip
  versions you know contain destructive/irreversible migrations without
  reading their release notes first. `postgres` itself only needs its own
  image bumped for a PostgreSQL minor/major upgrade, independent of
  application releases.
- **Verifying an upgrade**: after `docker compose up -d app`, tail logs
  until Flyway reports success and the container becomes healthy:

  ```bash
  docker compose -f infrastructure/compose/docker-compose.yml \
    --env-file infrastructure/compose/.env logs -f app
  ```

  Look for Flyway's `Successfully applied N migration(s)` line (or
  `Schema ... is up to date` if there was nothing to do), then confirm
  readiness:

  ```bash
  curl -f http://localhost:8080/actuator/health/readiness
  ```
- **Rollback policy**: Flyway does not auto-downgrade. Reverting to a
  previous application image against a database that already has newer
  migrations applied is not supported — restore the `postgres` volume from a
  backup taken before the upgrade (see "Restore" below) if a release must be
  rolled back.
- **First-run seeding**: on an empty database, the application creates the
  default organization named by `BIGCONTAINERS_SEED_DEFAULT_ORGANIZATION_NAME`
  and, if `BIGCONTAINERS_SEED_OWNER_USERNAME` and
  `BIGCONTAINERS_SEED_OWNER_PASSWORD` are both set, the first Owner account.
  See "Authentication and first Owner login" above - this is the only way to
  get a first login, and it must be set before the first start.

## Reverse proxy and TLS

This stack terminates HTTP only, on `BIGCONTAINERS_APP_PORT` (default
`8080`). Put a reverse proxy (e.g. nginx, Caddy, Traefik) in front of it for
TLS termination, and:

- Set `BIGCONTAINERS_APPLICATION_PUBLIC_BASE_URL` to the proxy's public
  HTTPS origin, not `http://localhost:8080`.
- Forward `X-Forwarded-Proto`, `X-Forwarded-Host`, and `X-Forwarded-Port` (or
  the proxy's equivalent single `Forwarded` header) to the `app` container,
  and make sure the proxy **overwrites** any such headers sent by the client
  rather than passing them through — otherwise a client could spoof its own
  scheme/host. `docker-compose.yml` sets
  `SERVER_FORWARD_HEADERS_STRATEGY=FRAMEWORK` so Spring's forwarded-header
  support is active and trusts headers reaching it; the proxy is what must
  keep those headers honest, per
  [ADR-0003](../../docs/adr/ADR-0003-authentication-and-session.md).
- This matters beyond cosmetics: OIDC redirect URIs and any other absolute
  URL the application generates are built from the forwarded scheme/host, so
  a proxy misconfiguration here breaks login.

## Backup and restore

### PostgreSQL

**Backup** (logical dump, safe to run while the container is up):

```bash
docker compose -f infrastructure/compose/docker-compose.yml \
  --env-file infrastructure/compose/.env exec -T postgres \
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom \
  > backup-$(date +%Y%m%d-%H%M%S).dump
```

**Restore** into a fresh (empty) `postgres-data` volume — restoring on top of
a live database with existing data is not supported by this procedure:

```bash
docker compose -f infrastructure/compose/docker-compose.yml \
  --env-file infrastructure/compose/.env stop app postgres

docker compose -f infrastructure/compose/docker-compose.yml \
  --env-file infrastructure/compose/.env rm -f postgres

docker volume rm bigcontainers_postgres-data

docker compose -f infrastructure/compose/docker-compose.yml \
  --env-file infrastructure/compose/.env up -d postgres

# wait for postgres to report healthy, then:
docker compose -f infrastructure/compose/docker-compose.yml \
  --env-file infrastructure/compose/.env exec -T postgres \
  pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists \
  < backup-20260101-120000.dump

docker compose -f infrastructure/compose/docker-compose.yml \
  --env-file infrastructure/compose/.env up -d app
```

Starting `app` afterwards lets Flyway reconcile the restored schema against
the running application version before serving traffic.

Note: PostgreSQL 18's official image stores data directly under
`/var/lib/postgresql` inside the container (not `/var/lib/postgresql/data`
as in pre-18 images); `docker-compose.yml` mounts the `postgres-data` volume
there accordingly. This only matters if you are adapting these commands to
inspect the volume's contents directly - the `pg_dump`/`pg_restore` commands
above are unaffected either way, since they go through the PostgreSQL wire
protocol, not the filesystem.

### Media (object storage)

Back up the entire bucket named by `BIGCONTAINERS_S3_BUCKET`, not just the
`garage-data` volume snapshot alone, so the same procedure works whether
Garage or an external S3-compatible endpoint is in use. This uses the AWS
CLI (Garage is S3-compatible, so it works as a normal `aws s3` endpoint) from
a throwaway container on the same Compose network:

**Backup**:

```bash
docker run --rm --network bigcontainers_default \
  -e AWS_ACCESS_KEY_ID="$BIGCONTAINERS_S3_ACCESS_KEY" \
  -e AWS_SECRET_ACCESS_KEY="$BIGCONTAINERS_S3_SECRET_KEY" \
  -e AWS_DEFAULT_REGION="$BIGCONTAINERS_S3_REGION" \
  -v "$(pwd)/media-backup:/backup" \
  amazon/aws-cli --endpoint-url http://garage:3900 \
  s3 sync s3://bigcontainers /backup
```

For an external S3-compatible endpoint, point `--endpoint-url` at that
endpoint instead (or drop the flag entirely for real AWS S3) and drop
`--network bigcontainers_default`. `bigcontainers_default` is the network
Compose creates for this stack (from the top-level `name: bigcontainers` in
`docker-compose.yml`); confirm the exact name with `docker network ls` if
you changed the project name.

**Restore** (sync back into an empty bucket, in the same direction
reversed):

```bash
docker run --rm --network bigcontainers_default \
  -e AWS_ACCESS_KEY_ID="$BIGCONTAINERS_S3_ACCESS_KEY" \
  -e AWS_SECRET_ACCESS_KEY="$BIGCONTAINERS_S3_SECRET_KEY" \
  -e AWS_DEFAULT_REGION="$BIGCONTAINERS_S3_REGION" \
  -v "$(pwd)/media-backup:/backup" \
  amazon/aws-cli --endpoint-url http://garage:3900 \
  s3 sync /backup s3://bigcontainers
```

Because PostgreSQL stores media metadata and object keys (not filenames or
bytes; see [ADR-0001](../../docs/adr/ADR-0001-application-stack.md)),
PostgreSQL and media backups are only consistent with each other if taken
close together. Prefer restoring both from backups taken in the same
maintenance window; a media restore that is older than the database restore
can leave metadata pointing at objects that are not yet back in the bucket.

## Notes

- `docker-compose.yml` deliberately has no image-level `HEALTHCHECK`
  dependency for `app`; the readiness check above is defined in Compose only,
  per ADR-0004.
- The `app` service's Docker healthcheck speaks raw HTTP over `/dev/tcp`
  from an explicit `bash -c` invocation rather than shelling out to
  `curl`/`wget`, because the published runtime image
  (`eclipse-temurin:25-jre`) has neither, and its default `/bin/sh` (`dash`)
  does not support `/dev/tcp` either. This was verified directly against
  that base image, including that a non-`UP` body and a refused connection
  both correctly fail the check.
