import "fake-indexeddb/auto";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AuditDatabase, auditPartition } from "../indexeddb/auditDatabase";
import { AuditOutbox, retryDelay } from "./auditOutbox";
import type { ContainerAudit } from "../../features/audits/auditApi";
import type { SessionPrincipal } from "../../features/identity/sessionApi";
import { auditIdentityEpoch, disableAuditRecovery, stopAuditIdentity } from "../sync/auditIdentity";

const principal: SessionPrincipal = {
  organizationId: "org",
  userId: "actor",
  username: "actor",
  displayName: "Actor",
  role: "OWNER",
};
const partition = auditPartition(principal);
const audit: ContainerAudit = {
  id: "audit",
  taskId: "task",
  batchId: "batch",
  containerAssetId: "case",
  state: "IN_PROGRESS",
  expectedRequirements: [],
  scans: [],
  findings: [],
  blockingReasons: [],
};
const scan = (operationId: string) => ({
  kind: "scan" as const,
  body: { operationId, code: "000000" },
});
describe("durable audit outbox", () => {
  let db: AuditDatabase;
  let outbox: AuditOutbox;
  beforeEach(async () => {
    db = new AuditDatabase(`audit-test-${crypto.randomUUID()}`);
    outbox = new AuditOutbox(db);
    await outbox.cache(principal, audit, { id: "case", publicCode: "CASE01", displayName: "Case" });
  });
  afterEach(async () => {
    vi.restoreAllMocks();
    await db.delete();
  });
  it("persists immutable operation payloads and FIFO order through database reopen, partitioned by actor", async () => {
    const command = scan("one");
    await outbox.enqueue(partition, audit, command);
    command.body.code = "changed";
    await outbox.enqueue(partition, audit, scan("two"));
    await outbox.enqueue("other:user", audit, scan("other"));
    const name = db.name;
    db.close();
    db = new AuditDatabase(name);
    outbox = new AuditOutbox(db);
    const rows = await outbox.list(partition);
    expect(rows.map((row) => row.operationId)).toEqual(["one", "two"]);
    expect(rows[0]?.command).toEqual(scan("one"));
    expect((await outbox.snapshot(partition, "task"))?.container?.displayName).toBe("Case");
  });
  it("commits authoritative acknowledgement and command removal atomically", async () => {
    await outbox.enqueue(partition, audit, scan("one"));
    const lease = (await outbox.acquire(partition, "drain"))!;
    const row = (await outbox.list(partition))[0]!;
    await outbox.acknowledge(lease, row, { ...audit, blockingReasons: ["Authoritative reply"] });
    expect(await outbox.list(partition)).toEqual([]);
    expect((await outbox.snapshot(partition, "task"))?.audit.blockingReasons).toEqual([
      "Authoritative reply",
    ]);
  });
  it("rolls back finding, photos and blobs together when staging cannot complete", async () => {
    const add = vi
      .spyOn(db.blobs, "add")
      .mockRejectedValue(new DOMException("Storage full", "QuotaExceededError"));
    await expect(
      outbox.findingWithPhotos(
        partition,
        audit,
        { kind: "finding", body: { operationId: "finding", type: "DAMAGED" } },
        [{ fileName: "photo.png", contentType: "image/png", bytes: new Uint8Array([1]).buffer }],
      ),
    ).rejects.toThrow("Storage full");
    expect(add).toHaveBeenCalled();
    expect(await outbox.list(partition)).toEqual([]);
    expect(await db.blobs.count()).toBe(0);
  });
  it("saves precise finding dependencies with photograph bytes and cleans canceled dependents", async () => {
    await outbox.findingWithPhotos(
      partition,
      audit,
      { kind: "finding", body: { operationId: "finding", type: "UNKNOWN_CODE" } },
      [{ fileName: "photo.png", contentType: "image/png", bytes: new Uint8Array([1, 2]).buffer }],
    );
    const rows = await outbox.list(partition);
    expect(rows[1]?.command).toMatchObject({ kind: "photo", findingOperationId: "finding" });
    expect((await db.blobs.get(rows[1]!.operationId))?.bytes.byteLength).toBe(2);
    await outbox.cancel(rows[0]!);
    expect(await outbox.list(partition)).toEqual([]);
    expect(await db.blobs.count()).toBe(0);
  });
  it("shares an exclusive fenced lease across database connections and recovers expired senders", async () => {
    const another = new AuditDatabase(db.name);
    const second = new AuditOutbox(another);
    try {
      await outbox.enqueue(partition, audit, scan("one"));
      const lease = (await outbox.acquire(partition, "drain"))!;
      expect(await second.acquire(partition, "complete")).toBeUndefined();
      await db.leases.update(partition, { expiresAt: Date.now() - 1 });
      const replacement = (await second.acquire(partition, "drain"))!;
      const row = (await outbox.list(partition))[0]!;
      await expect(outbox.sending(lease, row, 99)).rejects.toThrow("coordination changed");
      await expect(outbox.acknowledge(lease, row, audit)).rejects.toThrow("coordination changed");
      await outbox.release(lease);
      expect((await db.leases.get(partition))?.token).toBe(replacement.token);
      await second.sending(replacement, row);
      await second.acknowledge(replacement, row, audit);
      expect(await outbox.list(partition)).toEqual([]);
    } finally {
      another.close();
    }
  });
  it("blocks completion for failed photograph work and prevents new commands during the barrier", async () => {
    await outbox.enqueue(
      partition,
      audit,
      { kind: "photo", findingId: "finding", contentType: "image/jpeg", fileName: "damage.jpg" },
      new Uint8Array([1]).buffer,
    );
    const row = (await outbox.list(partition))[0]!;
    await db.commands.update(row.sequence!, { state: "failed" });
    const lease = (await outbox.acquire(partition, "complete"))!;
    await expect(outbox.completionBarrier(lease, "audit")).rejects.toThrow("photographs");
    await expect(outbox.enqueue(partition, audit, scan("new"))).rejects.toThrow(
      "completion is in progress",
    );
    await outbox.release(lease);
    await outbox.cancel(row);
    const next = (await outbox.acquire(partition, "complete"))!;
    await expect(outbox.completionBarrier(next, "audit")).resolves.toBeUndefined();
  });
  it("rejects obsolete local writes after another tab saves completion", async () => {
    await outbox.cache(principal, { ...audit, state: "COMPLETED" });
    await expect(outbox.enqueue(partition, audit, scan("late"))).rejects.toThrow(
      "already completed",
    );
  });
  it("refuses stale GET snapshots after another tab acknowledges a finding or completes", async () => {
    const revision = (await outbox.snapshot(partition, "task"))!.revision!;
    await outbox.enqueue(partition, audit, scan("new"));
    const lease = (await outbox.acquire(partition, "drain"))!;
    const row = (await outbox.list(partition))[0]!;
    const authoritative = {
      ...audit,
      findings: [
        { id: "finding", type: "DAMAGED" as const, detail: "Damage", sourceOperationId: "source" },
      ],
    };
    await outbox.acknowledge(lease, row, authoritative);
    const cached = await outbox.cache(principal, audit, undefined, revision);
    expect(cached?.audit.findings[0]?.sourceOperationId).toBe("source");
    const beforeCompletion = cached!.revision!;
    await outbox.cache(principal, { ...authoritative, state: "COMPLETED" });
    expect((await outbox.cache(principal, audit, undefined, beforeCompletion))?.audit.state).toBe(
      "COMPLETED",
    );
  });
  it("rejects late identity caching after logout rather than re-enabling cached recovery", async () => {
    const epoch = auditIdentityEpoch();
    await stopAuditIdentity();
    await expect(outbox.cache(principal, audit, undefined, undefined, epoch)).rejects.toThrow(
      "account changed",
    );
  });
  it("fences late recovery across connections before any account-stop broadcast arrives", async () => {
    const generation = await outbox.identityGeneration();
    const another = new AuditDatabase(db.name);
    try {
      await disableAuditRecovery(another);
      await expect(
        outbox.cache(principal, audit, undefined, undefined, auditIdentityEpoch(), generation),
      ).rejects.toThrow("saved audit account changed");
      expect((await db.identities.get("last-audit"))?.enabled).toBe(false);
      const freshGeneration = await outbox.identityGeneration();
      await outbox.cache(
        principal,
        audit,
        undefined,
        undefined,
        auditIdentityEpoch(),
        freshGeneration,
      );
      expect((await db.identities.get("last-audit"))?.enabled).toBe(true);
    } finally {
      another.close();
    }
  });
  it("records a durable logout fence before the first audit identity is cached", async () => {
    await db.identities.clear();
    const generation = await outbox.identityGeneration();
    const another = new AuditDatabase(db.name);
    try {
      await disableAuditRecovery(another);
      await expect(
        outbox.cache(principal, audit, undefined, undefined, auditIdentityEpoch(), generation),
      ).rejects.toThrow("saved audit account changed");
      expect(await db.identities.get("last-audit")).toMatchObject({
        enabled: false,
        generation: 1,
      });
    } finally {
      another.close();
    }
  });
  it("preserves completed state against late start replies but permits a different audit attempt", async () => {
    await outbox.cache(principal, { ...audit, state: "COMPLETED" });
    expect((await outbox.cache(principal, audit))?.audit.state).toBe("COMPLETED");
    expect((await outbox.cache(principal, { ...audit, id: "new-attempt" }))?.audit.id).toBe(
      "new-attempt",
    );
  });
  it("retains stable operation IDs during bounded retries and explicit failed retry", async () => {
    await outbox.enqueue(partition, audit, scan("stable"));
    const lease = (await outbox.acquire(partition, "drain"))!;
    let row = (await outbox.list(partition))[0]!;
    await outbox.failure(lease, row, "Interrupted", true);
    row = (await outbox.list(partition))[0]!;
    expect(row.nextAttemptAt).toBeGreaterThan(Date.now());
    expect(row.operationId).toBe("stable");
    await outbox.failure(lease, row, "Rejected", false);
    row = (await outbox.list(partition))[0]!;
    expect(row.state).toBe("failed");
    await outbox.release(lease);
    await outbox.retry(row);
    expect((await outbox.list(partition))[0]).toMatchObject({
      operationId: "stable",
      state: "pending",
      nextAttemptAt: 0,
    });
    expect(retryDelay(100)).toBe(60_000);
  });
});
