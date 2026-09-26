import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AssetPage } from "./AssetPage";

const api = vi.hoisted(() => ({
  getAsset: vi.fn(),
  getAssetModel: vi.fn(),
  listAssetHistory: vi.fn(),
}));

vi.mock("../identity/useSession", () => ({ useSession: () => ({ role: "VIEWER" }) }));
vi.mock("./inventoryApi", () => ({ ...api, errorMessage: (error: Error) => error.message }));
vi.mock("./AssetPlacementPanel", () => ({ AssetPlacementPanel: () => null }));
vi.mock("./MediaPanel", () => ({ MediaPanel: () => null }));
vi.mock("./PackingPanel", () => ({ PackingPanel: () => null }));
vi.mock("./AssetLabelExportDialog", () => ({
  AssetLabelExportDialog: ({ assetIds }: { assetIds: string[] }) => (
    <div role="dialog" aria-label="Export asset labels">
      {assetIds.join(", ")}
    </div>
  ),
}));

describe("AssetPage", () => {
  beforeEach(() => {
    api.getAsset.mockResolvedValue({
      kind: "ok",
      data: {
        id: "asset-1",
        assetModelId: "model-1",
        assetModelName: "Network AP",
        displayName: "Network AP 1",
        publicCode: "7K3MXY",
        unitNumber: 1,
        condition: "GOOD",
        lifecycleState: "ACTIVE",
        archived: false,
        metadataIncomplete: false,
        values: [],
      },
    });
    api.listAssetHistory.mockResolvedValue({ kind: "ok", data: [] });
    api.getAssetModel.mockResolvedValue({
      kind: "ok",
      data: { id: "model-1", canContainAssets: false },
    });
  });

  it("offers a Viewer a single-unit label reprint without mutation controls", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={["/inventory/assets/asset-1"]}>
        <Routes>
          <Route path="/inventory/assets/:assetId" element={<AssetPage />} />
        </Routes>
      </MemoryRouter>,
    );

    await user.click(await screen.findByRole("button", { name: "Export label" }));

    expect(screen.getByRole("dialog", { name: "Export asset labels" })).toHaveTextContent(
      "asset-1",
    );
    expect(screen.queryByRole("button", { name: "Edit details" })).not.toBeInTheDocument();
  });
});
