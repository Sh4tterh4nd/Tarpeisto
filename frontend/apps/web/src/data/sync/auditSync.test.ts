import "fake-indexeddb/auto";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AuditDatabase, auditPartition } from "../indexeddb/auditDatabase";
import { AuditOutbox } from "../outbox/auditOutbox";
import { AuditSync } from "./auditSync";
import type { ContainerAudit } from "../../features/audits/auditApi";
import type { SessionPrincipal } from "../../features/identity/sessionApi";

const session = vi.hoisted(() => ({ fetch: vi.fn() }));
vi.mock("../../features/identity/sessionApi", () => ({ fetchCurrentSession: session.fetch }));
vi.mock("../../platform/web/webConnectivityCapability", () => ({
  webConnectivityCapability: { getStatus: () => "online", subscribe: () => () => {} },
}));
const principal: SessionPrincipal = {
  organizationId: "org",
  userId: "user",
  username: "user",
  displayName: "User",
  role: "OWNER",
};
const partition = auditPartition(principal);
const audit: ContainerAudit = {
  id: "audit",
  taskId: "task",
  batchId: "batch",
  containerAssetId: "container",
  state: "IN_PROGRESS",
  expectedRequirements: [],
  scans: [],
  findings: [],
  blockingReasons: [],
};
describe("audit synchronization", () => {
  let db: AuditDatabase;
  let outbox: AuditOutbox;
  beforeEach(async () => {
    db = new AuditDatabase(`sync-${crypto.randomUUID()}`);
    outbox = new AuditOutbox(db);
    await outbox.cache(principal, audit);
    session.fetch.mockReset().mockResolvedValue({ kind: "authenticated", principal });
    document.cookie = "XSRF-TOKEN=current; path=/";
  });
  afterEach(async () => {
    vi.unstubAllGlobals();
    await db.delete();
  });
  it("retries the stable operation after a lost response, then acknowledges FIFO with fresh CSRF", async () => {
    await outbox.enqueue(partition, audit, {
      kind: "scan",
      body: { operationId: "first", code: "000000" },
    });
    await outbox.enqueue(partition, audit, {
      kind: "scan",
      body: { operationId: "second", code: "111111" },
    });
    const requests: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        requests.push(request.clone());
        if (requests.length === 1) throw new TypeError("Reply lost");
        return new Response(
          JSON.stringify({ ...audit, blockingReasons: [String(requests.length)] }),
          { status: 200, headers: { "content-type": "application/json" } },
        );
      }),
    );
    const sync = new AuditSync(principal, outbox);
    await sync.tick();
    expect(await outbox.list(partition)).toHaveLength(2);
    const first = (await outbox.list(partition))[0]!;
    await db.commands.update(first.sequence!, { nextAttemptAt: 0 });
    document.cookie = "XSRF-TOKEN=refreshed; path=/";
    await sync.tick();
    expect(await outbox.list(partition)).toEqual([]);
    expect(await Promise.all(requests.map((request) => request.json()))).toEqual([
      { operationId: "first", code: "000000" },
      { operationId: "first", code: "000000" },
      { operationId: "second", code: "111111" },
    ]);
    expect(requests[1]?.headers.get("X-XSRF-TOKEN")).toBe("refreshed");
    expect(
      requests.every((request) => request.headers.get("X-Tarpeisto-Audit-Actor") === partition),
    ).toBe(true);
    expect((await outbox.snapshot(partition, "task"))?.audit.blockingReasons).toEqual(["3"]);
  });
  it("keeps terminal rejection visible and blocks later commands until explicit correction", async () => {
    await outbox.enqueue(partition, audit, {
      kind: "scan",
      body: { operationId: "bad", code: "000000" },
    });
    await outbox.enqueue(partition, audit, {
      kind: "scan",
      body: { operationId: "later", code: "111111" },
    });
    const fetch = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          type: "about:blank",
          title: "Conflict",
          status: 409,
          detail: "Scan belongs to another audit",
        }),
        { status: 409, headers: { "content-type": "application/json" } },
      ),
    );
    vi.stubGlobal("fetch", fetch);
    const sync = new AuditSync(principal, outbox);
    await sync.tick();
    await sync.tick();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect((await outbox.list(partition))[0]).toMatchObject({
      state: "failed",
      error: "Scan belongs to another audit",
      operationId: "bad",
    });
  });
  it("never sends saved work under a switched live actor", async () => {
    await outbox.enqueue(partition, audit, {
      kind: "scan",
      body: { operationId: "saved", code: "000000" },
    });
    session.fetch.mockResolvedValue({
      kind: "authenticated",
      principal: { ...principal, userId: "another" },
    });
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    await new AuditSync(principal, outbox).tick();
    expect(fetch).not.toHaveBeenCalled();
    expect(await outbox.list(partition)).toHaveLength(1);
  });
});
