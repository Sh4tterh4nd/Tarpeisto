import { liveQuery } from "dexie";
import {
  auditDatabase,
  auditPartition,
  type AuditDatabase,
  type AuditCommand,
  type AuditLease,
  type CachedAudit,
  type QueuedAuditCommand,
} from "../indexeddb/auditDatabase";
import type { SessionPrincipal } from "../../features/identity/sessionApi";
import type { AuditContainer, ContainerAudit } from "../../features/audits/auditApi";
import { auditIdentityEpoch } from "../sync/auditIdentity";

export const LEASE_MS = 15_000;
export const retryDelay = (attempts: number) =>
  Math.min(60_000, 1000 * 2 ** Math.min(attempts - 1, 6));
export class AuditOutbox {
  constructor(readonly db: AuditDatabase = auditDatabase) {}
  key(partition: string, taskId: string) {
    return `${partition}/${taskId}`;
  }
  async cache(
    principal: SessionPrincipal,
    audit: ContainerAudit,
    container?: AuditContainer,
    expectedRevision?: number,
    expectedIdentityEpoch = auditIdentityEpoch(),
    expectedIdentityGeneration?: number,
  ) {
    const partition = auditPartition(principal);
    return this.db.transaction("rw", this.db.snapshots, this.db.identities, async () => {
      if (auditIdentityEpoch() !== expectedIdentityEpoch)
        throw new Error("The audit account changed. Verify your session before saving work.");
      const previous = await this.db.snapshots.get(this.key(partition, audit.taskId));
      const identity = await this.db.identities.get("last-audit");
      if (
        expectedIdentityGeneration !== undefined &&
        (identity?.generation ?? 0) !== expectedIdentityGeneration
      )
        throw new Error(
          "The saved audit account changed. Verify your session before recovering work.",
        );
      if (expectedRevision !== undefined && (previous?.revision ?? 0) !== expectedRevision)
        return previous;
      if (
        previous?.audit.id === audit.id &&
        previous?.audit.state === "COMPLETED" &&
        audit.state === "IN_PROGRESS"
      )
        return previous;
      await this.db.snapshots.put({
        revision: (previous?.revision ?? 0) + 1,
        key: this.key(partition, audit.taskId),
        partition,
        taskId: audit.taskId,
        audit,
        container: container ?? previous?.container,
      });
      if (auditIdentityEpoch() !== expectedIdentityEpoch)
        throw new Error("The audit account changed. Verify your session before saving work.");
      if (audit.id && audit.state === "IN_PROGRESS")
        await this.db.identities.put({
          id: "last-audit",
          principal,
          taskId: audit.taskId,
          enabled: true,
          generation: identity?.generation ?? 0,
        });
      return this.snapshot(partition, audit.taskId);
    });
  }
  snapshot(partition: string, taskId: string) {
    return this.db.snapshots.get(this.key(partition, taskId));
  }
  async identityGeneration() {
    return (await this.db.identities.get("last-audit"))?.generation ?? 0;
  }
  async enqueue(
    partition: string,
    audit: ContainerAudit,
    command: AuditCommand,
    bytes?: ArrayBuffer,
  ) {
    if (!audit.id || audit.state !== "IN_PROGRESS")
      throw new Error("Start the audit online before recording work.");
    const operationId = command.kind === "photo" ? crypto.randomUUID() : command.body.operationId;
    await this.db.transaction(
      "rw",
      this.db.commands,
      this.db.blobs,
      this.db.leases,
      this.db.snapshots,
      async () => {
        const snapshot = await this.snapshot(partition, audit.taskId);
        if (snapshot && snapshot.audit.state !== "IN_PROGRESS")
          throw new Error("This audit has already completed. Refresh its authoritative result.");
        const lease = await this.db.leases.get(partition);
        if (lease?.mode === "complete" && lease.expiresAt > Date.now())
          throw new Error(
            "Audit completion is in progress in another tab. Wait before recording more work.",
          );
        if (command.kind === "photo" && !bytes)
          throw new Error("Photograph bytes could not be saved.");
        if (
          command.kind === "photo" &&
          (!["image/png", "image/jpeg"].includes(command.contentType) ||
            !bytes?.byteLength ||
            bytes.byteLength > 10 * 1024 * 1024)
        )
          throw new Error("Choose a PNG or JPEG photograph up to 10 MB.");
        await this.db.commands.add({
          partition,
          auditId: audit.id!,
          taskId: audit.taskId,
          operationId,
          command,
          state: "pending",
          attempts: 0,
          nextAttemptAt: 0,
          progress: 0,
        });
        if (bytes) await this.db.blobs.add({ operationId, bytes });
      },
    );
    return operationId;
  }
  list(partition: string, auditId?: string) {
    return auditId
      ? this.db.commands
          .where("[partition+auditId]")
          .equals([partition, auditId])
          .sortBy("sequence")
      : this.db.commands.where("partition").equals(partition).sortBy("sequence");
  }
  async findingWithPhotos(
    partition: string,
    audit: ContainerAudit,
    command: Extract<AuditCommand, { kind: "finding" }>,
    photos: Array<{ fileName: string; contentType: string; bytes: ArrayBuffer }>,
  ) {
    return this.db.transaction(
      "rw",
      this.db.commands,
      this.db.blobs,
      this.db.leases,
      this.db.snapshots,
      async () => {
        const operationId = await this.enqueue(partition, audit, command);
        for (const photo of photos)
          await this.enqueue(
            partition,
            audit,
            {
              kind: "photo",
              findingOperationId: operationId,
              fileName: photo.fileName,
              contentType: photo.contentType,
            },
            photo.bytes,
          );
        return operationId;
      },
    );
  }
  subscribe(
    partition: string,
    taskId: string,
    listener: (value: { snapshot?: CachedAudit; commands: QueuedAuditCommand[] }) => void,
    onError: (error: unknown) => void,
  ) {
    const subscription = liveQuery(async () => ({
      snapshot: await this.snapshot(partition, taskId),
      commands: (await this.list(partition)).filter((row) => row.taskId === taskId),
    })).subscribe({ next: listener, error: onError });
    return () => subscription.unsubscribe();
  }
  async acquire(partition: string, mode: AuditLease["mode"], now = Date.now()) {
    return this.db.transaction("rw", this.db.leases, async () => {
      const previous = await this.db.leases.get(partition);
      if (previous && previous.expiresAt > now) return undefined;
      const lease: AuditLease = {
        partition,
        mode,
        token: crypto.randomUUID(),
        expiresAt: now + LEASE_MS,
      };
      await this.db.leases.put(lease);
      return lease;
    });
  }
  async assertLease(lease: AuditLease) {
    const current = await this.db.leases.get(lease.partition);
    if (!current || current.token !== lease.token || current.expiresAt <= Date.now())
      throw new Error("Audit coordination changed. Work will recover safely.");
  }
  async heartbeat(lease: AuditLease) {
    await this.db.transaction("rw", this.db.leases, async () => {
      await this.assertLease(lease);
      await this.db.leases.update(lease.partition, { expiresAt: Date.now() + LEASE_MS });
    });
  }
  async release(lease: AuditLease) {
    await this.db.transaction("rw", this.db.leases, async () => {
      if ((await this.db.leases.get(lease.partition))?.token === lease.token)
        await this.db.leases.delete(lease.partition);
    });
  }
  async acknowledge(lease: AuditLease, row: QueuedAuditCommand, audit?: ContainerAudit) {
    await this.db.transaction(
      "rw",
      this.db.commands,
      this.db.blobs,
      this.db.snapshots,
      this.db.leases,
      async () => {
        await this.assertLease(lease);
        if (audit) {
          const key = this.key(row.partition, row.taskId);
          const existing = await this.db.snapshots.get(key);
          await this.db.snapshots.put({
            ...existing,
            revision: (existing?.revision ?? 0) + 1,
            key,
            partition: row.partition,
            taskId: row.taskId,
            audit,
          });
        }
        await this.db.commands.delete(row.sequence!);
        await this.db.blobs.delete(row.operationId);
      },
    );
  }
  async failure(lease: AuditLease, row: QueuedAuditCommand, message: string, temporary: boolean) {
    await this.db.transaction("rw", this.db.commands, this.db.leases, async () => {
      await this.assertLease(lease);
      await this.db.commands.update(row.sequence!, {
        state: temporary ? "pending" : "failed",
        error: message,
        attempts: row.attempts + 1,
        nextAttemptAt: temporary ? Date.now() + retryDelay(row.attempts + 1) : 0,
        progress: 0,
      });
    });
  }
  async sending(lease: AuditLease, row: QueuedAuditCommand, progress = 0) {
    await this.db.transaction("rw", this.db.commands, this.db.leases, async () => {
      await this.assertLease(lease);
      await this.db.commands.update(row.sequence!, { state: "sending", progress });
    });
  }
  async retry(row: QueuedAuditCommand) {
    await this.db.commands.update(row.sequence!, {
      state: "pending",
      nextAttemptAt: 0,
      error: undefined,
      progress: 0,
    });
  }
  async cancel(row: QueuedAuditCommand) {
    await this.db.transaction("rw", this.db.commands, this.db.blobs, this.db.leases, async () => {
      const lease = await this.db.leases.get(row.partition);
      if (lease && lease.expiresAt > Date.now())
        throw new Error("Wait for synchronization to stop before canceling.");
      const current = await this.db.commands.get(row.sequence!);
      if (
        current &&
        current.state !== "failed" &&
        (current.state === "sending" || current.attempts > 0)
      )
        throw new Error(
          "This operation may have reached the server. Synchronize its confirmation before correcting it.",
        );
      const dependents = (await this.list(row.partition)).filter(
        (candidate) =>
          candidate.operationId === row.operationId ||
          (candidate.command.kind === "photo" &&
            candidate.command.findingOperationId === row.operationId),
      );
      for (const candidate of dependents) {
        await this.db.commands.delete(candidate.sequence!);
        await this.db.blobs.delete(candidate.operationId);
      }
    });
  }
  async completionBarrier(lease: AuditLease, auditId: string) {
    await this.db.transaction("r", this.db.commands, this.db.leases, async () => {
      await this.assertLease(lease);
      if ((await this.list(lease.partition, auditId)).length)
        throw new Error(
          "Synchronize or cancel all queued audit work and photographs before completion.",
        );
    });
  }
}
export const auditOutbox = new AuditOutbox();
export type { AuditCommand, QueuedAuditCommand } from "../indexeddb/auditDatabase";
