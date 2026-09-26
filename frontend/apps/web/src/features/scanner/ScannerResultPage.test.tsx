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

function renderResult() {
  render(
    <MemoryRouter initialEntries={["/scan/assets/container-1"]}>
      <Routes>
        <Route path="/scan/assets/:assetId" element={<ScannerResultPage />} />
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
});
