import "fake-indexeddb/auto";
import { act, render, renderHook, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { auditDatabase, auditPartition } from "../../data/indexeddb/auditDatabase";
import { auditOutbox } from "../../data/outbox/auditOutbox";
import { stopAuditIdentity } from "../../data/sync/auditIdentity";
import { SessionProvider } from "./SessionProvider";
import { useSession } from "./useSession";
import { RequireRole } from "./RequireRole";
import type { SessionPrincipal } from "./sessionApi";
import type { ContainerAudit } from "../audits/auditApi";

const principal: SessionPrincipal = {
  organizationId: "org",
  userId: "user",
  username: "auditor",
  displayName: "Auditor",
  role: "OPERATOR_AUDITOR",
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
describe("active-audit identity recovery", () => {
  beforeEach(async () => {
    await auditDatabase.transaction("rw", auditDatabase.tables, async () => {
      for (const table of auditDatabase.tables) await table.clear();
    });
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Network offline")));
  });
  afterEach(() => vi.unstubAllGlobals());
  it("recovers only an already-started active audit after network failure", async () => {
    await auditOutbox.cache(principal, audit);
    const { result } = renderHook(() => useSession(), { wrapper: SessionProvider });
    await waitFor(() => expect(result.current.status).toBe("offline-audit"));
    expect(result.current.offlineAuditTaskId).toBe("task");
    expect(result.current.principal?.userId).toBe("user");
  });
  it("routes offline inventory navigation back to the recovered task", async () => {
    await auditOutbox.cache(principal, audit);
    render(
      <SessionProvider>
        <MemoryRouter initialEntries={["/inventory"]}>
          <Routes>
            <Route
              path="/inventory"
              element={
                <RequireRole allow={["OPERATOR_AUDITOR"]}>
                  <div>Inventory records</div>
                </RequireRole>
              }
            />
            <Route
              path="/audits/tasks/task"
              element={
                <RequireRole allow={["OPERATOR_AUDITOR"]}>
                  <div>Recovered audit</div>
                </RequireRole>
              }
            />
          </Routes>
        </MemoryRouter>
      </SessionProvider>,
    );
    expect(await screen.findByText("Recovered audit")).toBeInTheDocument();
    expect(screen.queryByText("Inventory records")).not.toBeInTheDocument();
  });
  it("refuses cached recovery after logout while preserving that actor's queued work", async () => {
    await auditOutbox.cache(principal, audit);
    await auditOutbox.enqueue(auditPartition(principal), audit, {
      kind: "scan",
      body: { operationId: "op", code: "000000" },
    });
    const { result } = renderHook(() => useSession(), { wrapper: SessionProvider });
    await waitFor(() => expect(result.current.status).toBe("offline-audit"));
    await act(async () => {
      await stopAuditIdentity();
    });
    expect(result.current.status).toBe("anonymous");
    expect(result.current.principal).toBeUndefined();
    expect(await auditOutbox.list(auditPartition(principal))).toHaveLength(1);
    const second = renderHook(() => useSession(), { wrapper: SessionProvider });
    await waitFor(() => expect(second.result.current.status).toBe("anonymous"));
  });
  it("never recovers a completed cached audit", async () => {
    await auditOutbox.cache(principal, audit);
    await auditOutbox.cache(principal, { ...audit, state: "COMPLETED" });
    const { result } = renderHook(() => useSession(), { wrapper: SessionProvider });
    await waitFor(() => expect(result.current.status).toBe("anonymous"));
  });
});
