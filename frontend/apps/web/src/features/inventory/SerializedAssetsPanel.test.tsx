import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SerializedAssetsPanel } from "./SerializedAssetsPanel";

const api = vi.hoisted(() => ({ listAssets: vi.fn() }));

vi.mock("./inventoryApi", () => ({
  ...api,
  errorMessage: (error: Error) => error.message,
}));

vi.mock("./AssetLabelExportDialog", () => ({
  AssetLabelExportDialog: ({ assetIds }: { assetIds: string[] }) => (
    <div role="dialog" aria-label="Export asset labels">
      Selected: {assetIds.join(", ")}
    </div>
  ),
}));

describe("SerializedAssetsPanel", () => {
  beforeEach(() => {
    api.listAssets.mockResolvedValue({
      kind: "ok",
      data: [
        {
          id: "asset-1",
          displayName: "Network AP 1",
          publicCode: "7K3MXY",
          condition: "GOOD",
          lifecycleState: "ACTIVE",
          archived: false,
          metadataIncomplete: false,
        },
      ],
    });
  });

  it("lets a read-only user select units and open the label export flow", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <SerializedAssetsPanel
          assetModelId="model-1"
          modelName="Network AP"
          containerCapable={false}
          canManage={false}
        />
      </MemoryRouter>,
    );

    const exportButton = await screen.findByRole("button", { name: "Export labels" });
    expect(exportButton).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Create unit" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("checkbox", { name: "Select Network AP 1" }));
    await user.click(screen.getByRole("button", { name: "Export labels (1)" }));

    expect(screen.getByRole("dialog", { name: "Export asset labels" })).toHaveTextContent(
      "Selected: asset-1",
    );
  });
});
