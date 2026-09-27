# Tarpeisto Docker Swarm deployment

`docker-compose.yml` is a Docker Swarm stack definition despite its conventional file name. It
starts the application, PostgreSQL, and a single-node [SeaweedFS](https://github.com/seaweedfs/seaweedfs)
`weed mini` S3 service. SeaweedFS creates the `tarpeisto` bucket on its first start.

The stack deliberately does not use a `.env` file. Non-secret settings are visible in the stack;
credentials are external Docker Swarm secrets, mounted as files.

## Before the first deployment

1. Choose exactly one node for PostgreSQL and SeaweedFS data and label it. The named `local`
   volumes are local to that node, so this is a single-node stateful deployment, not highly
   available storage. Do not give the `primary` value to another node while these volumes exist.

   ```bash
   docker node update --label-add tarpeisto-data=primary <node-name>
   ```

2. Create the three external secrets. Use a generated PostgreSQL password and a SeaweedFS S3
   access key/secret pair. Do not put these values in the stack file.

   ```bash
   printf '%s' '<postgres-password>' | docker secret create tarpeisto_postgres_password -
   printf '%s' '<s3-access-key>' | docker secret create tarpeisto_s3_access_key -
   printf '%s' '<s3-secret-key>' | docker secret create tarpeisto_s3_secret_key -
   ```

3. Edit the non-secret values in `docker-compose.yml`: the released application image (prefer a
   digest), public base URL, and display name. Keep the SeaweedFS endpoint,
   region, bucket, path-style, and chunked-encoding configuration together unless you intentionally
   use external S3.

4. Deploy the stack:

   ```bash
   docker stack deploy -c infrastructure/compose/docker-compose.yml tarpeisto
   docker service logs -f tarpeisto_app
   ```

   PostgreSQL gates application startup, so Swarm retries the application until the database is
   reachable. SeaweedFS does not gate startup; media actions can be temporarily unavailable until
   its health check is passing. Confirm application readiness through `/actuator/health/readiness`,
   then open the site and complete the one-time organization and Owner setup page.

## Secrets and configuration

The application imports `/run/secrets/` as a Spring `configtree`. Secret target names are Spring
property names, so `spring.datasource.password` and `tarpeisto.s3.secret-key` never appear as
environment variables. PostgreSQL reads its secret with `POSTGRES_PASSWORD_FILE`. SeaweedFS reads
the same S3 credentials from secret files in its startup shell and receives no plaintext
credential in the stack.

For optional OIDC, create a separate external Swarm secret and add it to `app` with target
`tarpeisto.authentication.oidc.client-secret`; then set the non-secret OIDC environment
settings in the stack. Do not substitute an OIDC client secret directly into YAML.

Docker Swarm secrets are immutable. Rotate a secret by creating a new secret name, updating the
stack's external secret mapping, and redeploying. Remove the old secret only after the rollout is
healthy.

## Traefik deployment

If Traefik owns the public endpoint, use `docker-stack.traefik.example.yml` as the starting point
instead of publishing port 8080. It attaches the app to an existing `traefik` overlay network and
places all Traefik labels under `deploy.labels`, which is required for Swarm service discovery.

Create the external overlay network once if Traefik has not already created it:

```bash
docker network create --driver overlay --attachable traefik
```

The canonical stack publishes port 8080 only as a reverse-proxy backend. It is not a directly usable
HTTP deployment: secure session cookies require HTTPS. Do not expose that port directly to the
internet. Put it behind a TLS-terminating reverse proxy, or use the Traefik example and ensure the
proxy overwrites forwarded headers before they reach the app.

## Stateful data, backups, and upgrades

PostgreSQL and SeaweedFS are intentionally constrained to the one `tarpeisto-data=primary`
node because their default `local` volumes do not move with a Swarm task. Do not remove the label
or scale either service above one replica. For node failover or multi-node storage, use a tested
shared-volume or managed database/object-store solution before changing the constraints.

Run these commands on the primary node. Replace `tarpeisto` if you deploy with another stack
name, and keep the database and media backups from the same maintenance window.

### PostgreSQL backup and restore

```bash
POSTGRES_CONTAINER="$(docker ps -q --filter label=com.docker.swarm.service.name=tarpeisto_postgres)"
docker exec "$POSTGRES_CONTAINER" pg_dump -U tarpeisto -d tarpeisto --format=custom \
  > tarpeisto-$(date +%Y%m%d-%H%M%S).dump
```

Restoring replaces database objects. Stop the app first, restore into PostgreSQL, then start the
app so Flyway can validate the schema:

```bash
docker service scale tarpeisto_app=0
POSTGRES_CONTAINER="$(docker ps -q --filter label=com.docker.swarm.service.name=tarpeisto_postgres)"
docker exec -i "$POSTGRES_CONTAINER" pg_restore -U tarpeisto -d tarpeisto \
  --clean --if-exists < tarpeisto-20260927-120000.dump
docker service scale tarpeisto_app=1
```

### SeaweedFS media backup and restore

Use a one-off AWS CLI Swarm service so S3 credentials remain secret mounts. Create the host backup
directory on the primary node first, for example `/srv/tarpeisto-backup/media`.

```bash
docker service create --name tarpeisto-s3-backup --restart-condition none \
  --network tarpeisto_tarpeisto \
  --constraint node.labels.tarpeisto-data==primary \
  --secret source=tarpeisto_s3_access_key,target=s3_access_key \
  --secret source=tarpeisto_s3_secret_key,target=s3_secret_key \
  --mount type=bind,src=/srv/tarpeisto-backup,dst=/backup \
  --entrypoint /bin/sh amazon/aws-cli:2 -ec \
  'export AWS_ACCESS_KEY_ID="$(cat /run/secrets/s3_access_key)"; export AWS_SECRET_ACCESS_KEY="$(cat /run/secrets/s3_secret_key)"; export AWS_DEFAULT_REGION=us-east-1; aws --endpoint-url http://seaweedfs:8333 s3 sync s3://tarpeisto /backup/media'
docker service logs -f tarpeisto-s3-backup
docker service rm tarpeisto-s3-backup
```

For restore, create the same temporary service but reverse the final sync arguments to
`s3 sync /backup/media s3://tarpeisto`. Restore to an empty bucket or confirm the intended
overwrite behavior first. Do not add `--delete` unless the backup is the complete desired bucket
state.

Run application upgrades by changing only to a verified immutable image version or digest and
redeploying. Flyway runs in the application at startup; it does not support an automatic database
downgrade.

## External S3

To use AWS S3 or another managed S3-compatible service, remove the `seaweedfs` service and its
volume, set the endpoint/region/bucket/path-style/chunked-encoding settings for that provider, and
keep the S3 credential secret targets on `app`. For AWS S3, omit the endpoint and set path-style
access to `false`; chunked encoding remains enabled by default.

### Cloudflare R2

Create the bucket and an R2 API token with Object Read & Write permission restricted to that
bucket. Create the Swarm access-key and secret-key secrets from the R2 token's Access Key ID and
Secret Access Key, then map them to the existing `tarpeisto.s3.access-key` and
`tarpeisto.s3.secret-key` targets on `app`.

Remove the `seaweedfs` service and the `seaweedfs-data` volume, then replace the application S3
settings with the following. Use the jurisdiction-specific `eu.r2.cloudflarestorage.com` hostname
for an EU-jurisdiction R2 bucket; otherwise use `r2.cloudflarestorage.com`.

```yaml
environment:
  TARPEISTO_S3_ENABLED: "true"
  TARPEISTO_S3_ENDPOINT: https://<cloudflare-account-id>.r2.cloudflarestorage.com
  TARPEISTO_S3_REGION: auto
  TARPEISTO_S3_BUCKET: tarpeisto
  TARPEISTO_S3_PATH_STYLE_ACCESS: "true"
  TARPEISTO_S3_CHUNKED_ENCODING: "false"
```

R2 requires chunked transfer encoding to be disabled for AWS SDK for Java v2 uploads. The bucket
remains private: Tarpeisto authorizes and streams media server-side, so an `r2.dev` URL, custom
domain, and browser CORS policy are not required.
