import "fake-indexeddb/auto";
import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { auditDatabase, auditPartition } from "../../data/indexeddb/auditDatabase";
import { auditOutbox } from "../../data/outbox/auditOutbox";
import { useAuditQueue } from "./useAuditQueue";
import type { ContainerAudit } from "./auditApi";
import type { SessionPrincipal } from "../identity/sessionApi";

const principal: SessionPrincipal = {
  organizationId: "org",
  userId: "user",
  username: "user",
  displayName: "User",
  role: "OWNER",
};
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
vi.mock("../identity/useSession", () => ({
  useSession: () => ({ principal, status: "offline-audit" }),
}));
vi.mock("../../platform/web/webConnectivityCapability", () => ({
  webConnectivityCapability: { getStatus: () => "offline", subscribe: () => () => {} },
}));
describe("useAuditQueue", () => {
  beforeEach(async () => {
    vi.restoreAllMocks();
    await auditDatabase.transaction("rw", auditDatabase.tables, async () => {
      for (const table of auditDatabase.tables) await table.clear();
    });
    await auditOutbox.cache(principal, audit, {
      id: "container",
      displayName: "Case",
      publicCode: "000000",
    });
  });
  it("recovers saved work after hook remount without treating pending scans as authoritative", async () => {
    const first = renderHook(() => useAuditQueue("task"));
    await waitFor(() => expect(first.result.current.audit?.id).toBe("audit"));
    await act(async () => {
      await first.result.current.enqueue({
        kind: "scan",
        body: { operationId: "stable", code: "000000" },
      });
    });
    await waitFor(() => expect(first.result.current.commands).toHaveLength(1));
    expect(first.result.current.audit?.scans).toEqual([]);
    expect(first.result.current.completionBlocked).toBe(true);
    first.unmount();
    const second = renderHook(() => useAuditQueue("task"));
    await waitFor(() => expect(second.result.current.commands[0]?.operationId).toBe("stable"));
    expect(second.result.current.container?.displayName).toBe("Case");
    expect(second.result.current.syncStatus).toBe("Offline");
  });
  it("shows unsaved work on quota failure and retains no misleading pending operation", async () => {
    const hook = renderHook(() => useAuditQueue("task"));
    await waitFor(() => expect(hook.result.current.audit?.id).toBe("audit"));
    vi.spyOn(auditOutbox, "enqueue").mockRejectedValue(
      new DOMException("Storage full", "QuotaExceededError"),
    );
    await act(async () => {
      await hook.result.current.enqueue({
        kind: "scan",
        body: { operationId: "failed", code: "000000" },
      });
    });
    expect(hook.result.current.error).toContain("Work has not been saved");
    expect(await auditOutbox.list(auditPartition(principal))).toEqual([]);
  });
});
