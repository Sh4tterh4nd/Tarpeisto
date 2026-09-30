import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuditTaskPage } from "./AuditTaskPage";

const api = vi.hoisted(() => ({ getAuditContainer: vi.fn(), getAuditTask: vi.fn() }));

vi.mock("./auditApi", () => ({
  completeAudit: vi.fn(),
  getAuditContainer: api.getAuditContainer,
  getAuditTask: api.getAuditTask,
  moveAuditScanHere: vi.fn(),
  observeAuditConsumable: vi.fn(),
  recordAuditFinding: vi.fn(),
  scanAudit: vi.fn(),
  startAudit: vi.fn(),
  undoAuditScan: vi.fn(),
}));
vi.mock("../scanner/ScannerViewport", () => ({ ScannerViewport: () => <div>Camera</div> }));
vi.mock("../identity/useSession", () => ({ useSession: () => ({ role: "OPERATOR_AUDITOR" }) }));

describe("AuditTaskPage", () => {
  beforeEach(() => {
    api.getAuditContainer.mockResolvedValue({
      kind: "ok",
      data: { id: "container-1", displayName: "Child case", publicCode: "CHILD1" },
    });
    api.getAuditTask.mockResolvedValue({
      kind: "ok",
      data: {
        taskId: "task-1",
        batchId: "batch-1",
        containerAssetId: "container-1",
        state: "BLOCKED",
        expectedRequirements: [],
        scans: [],
        findings: [],
        blockingReasons: ["Complete child audit task-child"],
      },
    });
  });

  it("explains persistent active-audit operation and a child-task blocker before audit start", async () => {
    render(
      <MemoryRouter initialEntries={["/audits/tasks/task-1"]}>
        <Routes>
          <Route path="/audits/tasks/:taskId" element={<AuditTaskPage />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByText(/saved on this device/)).toBeInTheDocument();
    expect(
      await screen.findByRole("heading", { name: "Container audit: Child case" }),
    ).toBeInTheDocument();
    expect(screen.getByText(/CHILD1/)).toBeInTheDocument();
    expect(screen.getByText(/Complete child audit task-child/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start audit" })).toBeInTheDocument();
  });

  it("shows the volunteer actor on scans and findings while older snapshots remain readable", async () => {
    api.getAuditTask.mockResolvedValue({
      kind: "ok",
      data: {
        id: "audit-1",
        taskId: "task-1",
        batchId: "batch-1",
        containerAssetId: "container-1",
        state: "IN_PROGRESS",
        expectedRequirements: [],
        blockingReasons: [],
        scans: [
          {
            id: "scan-1",
            operationId: "op-1",
            assetId: "asset-1",
            assetCode: "CHILD1",
            outcome: "EXPECTED_EXACT",
            scannedAt: "2026-09-30T12:00:00Z",
            undone: false,
            contextSnapshot: "{}",
            recordedByUserId: "volunteer-1",
            recordedByDisplayName: "Volunteer One",
          },
          {
            id: "scan-old",
            operationId: "op-old",
            assetId: "asset-old",
            assetCode: "OLD001",
            outcome: "EXTRA",
            scannedAt: "2026-09-30T11:00:00Z",
            undone: true,
            contextSnapshot: "{}",
          },
        ],
        findings: [
          {
            id: "finding-1",
            assetId: "asset-1",
            type: "DAMAGED",
            note: "Cracked cover",
            detail: "{}",
            recordedByUserId: "volunteer-2",
            recordedByDisplayName: "Volunteer Two",
          },
        ],
      },
    });
    render(
      <MemoryRouter initialEntries={["/audits/tasks/task-1"]}>
        <Routes>
          <Route path="/audits/tasks/:taskId" element={<AuditTaskPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText("Recorded by Volunteer One")).toBeInTheDocument();
    expect(screen.getByText(/Cracked cover · Recorded by Volunteer Two/)).toBeInTheDocument();
    expect(screen.getByText(/OLD001/)).toBeInTheDocument();
  });
});
