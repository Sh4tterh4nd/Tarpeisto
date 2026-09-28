import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ScannerResultPage } from "./ScannerResultPage";

const state = vi.hoisted(() => ({ role: "OWNER" }));
const api = vi.hoisted(() => ({
  getScannedAsset: vi.fn(),
  getScannedAssetModel: vi.fn(),
  getScannedAssetPlacement: vi.fn(),
  listScannedContainerContents: vi.fn(),
  listScannedContainerStock: vi.fn(),
  restoreScannedAsset: vi.fn(),
  launchContainerAudit: vi.fn(),
  scannerErrorMessage: vi.fn((error: Error) => error.message),
}));

vi.mock("../identity/useSession", () => ({
  useSession: () => ({ role: state.role }),
}));
vi.mock("./scannerApi", () => api);

const lostContainer = {
  id: "container-1",
  assetModelId: "model-1",
  assetModelName: "Network box",
  publicCode: "7K3MXY",
  unitNumber: 2,
  individualName: "Mobile network box",
  displayName: "Mobile network box",
  condition: "DAMAGED",
  lifecycleState: "LOST",
  purchaseDate: undefined,
  archived: false,
  metadataIncomplete: false,
  values: [],
  createdAt: "2026-09-26T00:00:00Z",
  updatedAt: "2026-09-26T00:00:00Z",
};

const activeContainer = { ...lostContainer, lifecycleState: "ACTIVE" };

function renderResult(scanned = false) {
  render(
    <MemoryRouter
      initialEntries={[
        scanned
          ? { pathname: "/scan/assets/container-1", state: { scannedCode: "7K3MXY" } }
          : "/scan/assets/container-1",
      ]}
    >
      <Routes>
        <Route path="/scan/assets/:assetId" element={<ScannerResultPage />} />
        <Route path="/audits/tasks/:taskId" element={<div>Audit task opened</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("ScannerResultPage", () => {
  beforeEach(() => {
    state.role = "OWNER";
    api.getScannedAsset.mockResolvedValue({ kind: "ok", data: lostContainer });
    api.getScannedAssetModel.mockResolvedValue({ kind: "ok", data: { canContainAssets: true } });
    api.getScannedAssetPlacement.mockResolvedValue({
      kind: "ok",
      data: { assetId: "container-1", effectivePathText: "HQ / Shelf A / Mobile network box" },
    });
    api.listScannedContainerContents.mockResolvedValue({
      kind: "ok",
      data: [
        { assetId: "child-1", effectivePath: ["HQ", "Cable 1"] },
        { assetId: "child-2", effectivePath: ["HQ", "Access point 1"] },
      ],
    });
    api.listScannedContainerStock.mockResolvedValue({
      kind: "ok",
      data: [{ id: "stock-1", assetModelName: "Gaffer tape", quantity: 2, stockUnitLabel: "roll" }],
    });
    api.restoreScannedAsset.mockResolvedValue({
      kind: "ok",
      data: { ...lostContainer, lifecycleState: "ACTIVE" },
    });
    api.launchContainerAudit.mockResolvedValue({ kind: "ok", data: { taskId: "audit-task-1" } });
  });

  it("shows a prominent lost alert, direct container context, and manager restore action", async () => {
    const user = userEvent.setup();
    renderResult();

    expect(await screen.findByText("Mobile network box")).toBeInTheDocument();
    expect(screen.getByText(/This asset is marked lost/)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Container summary" })).toBeInTheDocument();
    expect(screen.getByText("Direct contents (2)")).toBeInTheDocument();
    expect(screen.getByText("Cable 1")).toBeInTheDocument();
    expect(screen.getByText(/Gaffer tape: 2 roll/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Restore asset" }));
    await waitFor(() => expect(api.restoreScannedAsset).toHaveBeenCalledWith("container-1"));
    expect(screen.queryByText(/This asset is marked lost/)).not.toBeInTheDocument();
  });

  it("does not offer lifecycle restoration to an operator", async () => {
    state.role = "OPERATOR_AUDITOR";
    renderResult();

    await screen.findByText("Mobile network box");
    expect(screen.queryByRole("button", { name: "Restore asset" })).not.toBeInTheDocument();
  });

  it("does not present unavailable container contents as an empty container", async () => {
    api.listScannedContainerContents.mockResolvedValue({
      kind: "error",
      error: new Error("Contents request failed"),
    });
    renderResult();

    expect(await screen.findByText(/Direct contents could not be loaded/)).toBeInTheDocument();
    expect(
      screen.queryByText("No direct asset contents are currently recorded."),
    ).not.toBeInTheDocument();
  });

  it("offers an authorized user an audit prompt only after a genuine scanner navigation", async () => {
    const user = userEvent.setup();
    api.getScannedAsset.mockResolvedValue({ kind: "ok", data: activeContainer });
    renderResult(true);
    expect(
      await screen.findByRole("dialog", { name: "Start a container audit?" }),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Not now" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Start audit" })).toBeInTheDocument();
  });

  it("does not offer a Viewer an audit prompt", async () => {
    state.role = "VIEWER";
    api.getScannedAsset.mockResolvedValue({ kind: "ok", data: activeContainer });
    renderResult(true);
    await screen.findByText("Mobile network box");
    expect(
      screen.queryByRole("dialog", { name: "Start a container audit?" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Start audit" })).not.toBeInTheDocument();
  });

  it("starts the audit and opens the returned task", async () => {
    const user = userEvent.setup();
    api.getScannedAsset.mockResolvedValue({ kind: "ok", data: activeContainer });
    renderResult(true);
    await user.click(await screen.findByRole("button", { name: "Start audit" }));
    await waitFor(() =>
      expect(api.launchContainerAudit).toHaveBeenCalledWith(
        "container-1",
        "7K3MXY",
        expect.any(String),
      ),
    );
    expect(await screen.findByText("Audit task opened")).toBeInTheDocument();
  });

  it("dismisses a failed launch prompt and retries with the same operation", async () => {
    const user = userEvent.setup();
    api.launchContainerAudit
      .mockResolvedValueOnce({ kind: "error", error: new Error("Launch failed") })
      .mockResolvedValueOnce({ kind: "ok", data: { taskId: "audit-task-1" } });
    api.getScannedAsset.mockResolvedValue({ kind: "ok", data: activeContainer });
    renderResult(true);
    await user.click(await screen.findByRole("button", { name: "Start audit" }));
    expect(
      await screen.findByText(/Could not start the container audit: Launch failed/),
    ).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const firstOperationId = api.launchContainerAudit.mock.calls[0]?.[2];
    expect(firstOperationId).toEqual(expect.any(String));
    await user.click(screen.getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(screen.getByText("Audit task opened")).toBeInTheDocument());
    expect(api.launchContainerAudit.mock.calls[1]?.[2]).toBe(firstOperationId);
  });
});
