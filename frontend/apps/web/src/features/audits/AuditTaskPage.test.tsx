import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuditTaskPage } from "./AuditTaskPage";

const api = vi.hoisted(() => ({ getAuditTask: vi.fn() }));

vi.mock("./auditApi", () => ({
  completeAudit: vi.fn(),
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

  it("explains online-only operation and a child-task blocker before audit start", async () => {
    render(
      <MemoryRouter initialEntries={["/audits/tasks/task-1"]}>
        <Routes>
          <Route path="/audits/tasks/:taskId" element={<AuditTaskPage />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByText(/Online-only/)).toBeInTheDocument();
    expect(screen.getByText(/Complete child audit task-child/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start audit" })).toBeInTheDocument();
  });
});
