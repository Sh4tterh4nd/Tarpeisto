# Upgrade to Tarpeisto

This release changes the deployment identity and application namespace. Plan a short maintenance
window and keep a verified database and object-storage backup.

1. Publish the new image before changing a running stack, then update the stack to use its immutable
   version or digest. Ask users to synchronize or finish pending client actions before the
   maintenance window.
2. A new Swarm stack name creates new stack-prefixed local-volume names. Choose one safe path for
   the first rollout:
   - Keep the current stack name for the first rollout, retaining the current volume attachment.
   - Declare the current named volumes as `external` and explicitly adopt them in the new stack.
   - Stop stateful services, take a verified backup, and copy the existing named-volume contents to
     newly created volumes before starting the new stack.

   Do not remove, recreate, or detach the source volumes until the migrated application and a
   restore rehearsal are verified. This applies to both PostgreSQL and SeaweedFS data volumes.
3. Rename the PostgreSQL database and role to the new deployment names, or configure the new
   datasource settings to continue using the existing database and role during a staged migration.
   `POSTGRES_PASSWORD_FILE` initializes a password only for a new data directory; when retaining a
   PostgreSQL volume, the renamed secret's content must match the existing database-role password
   unless that password is changed inside PostgreSQL first.
4. Recreate deployment secrets with their renamed secret names and update every configuration-tree
   target and environment variable to the Tarpeisto prefix. Mount the same credential content for
   retained database and object-store data unless the corresponding stored credentials have been
   changed deliberately.
5. Copy existing bucket objects to the renamed bucket before enabling media writes. Preserve each
   object key exactly, verify object count and checksums after the copy, and retain the source bucket
   until the new deployment has been restored and checked.
6. The first startup deliberately logs everyone out once, because stored server-side sessions contain
   serialized principal types from the previous application namespace.

Flyway schema history and existing schema object names are retained. Do not edit applied migration
files or alter object keys as part of this upgrade.
