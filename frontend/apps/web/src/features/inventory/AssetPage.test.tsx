import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppError } from "@tarpeisto/api-client";
import { AssetPage } from "./AssetPage";
import { AssetDetailsEditor } from "./AssetDetailsEditor";
import { AssetContentsCard } from "./AssetContentsCard";
import type { AssetRecord, AssetPlacementRecord } from "./inventoryApi";

const api = vi.hoisted(() => ({
  getAsset: vi.fn(),
  getAssetModel: vi.fn(),
  getAssetPlacement: vi.fn(),
  listAssetRepairs: vi.fn(),
  listAssetHistory: vi.fn(),
  listSealHistory: vi.fn(),
  setAssetArchived: vi.fn(),
  listCustomFields: vi.fn(),
  listCustomFieldOptions: vi.fn(),
  listLocations: vi.fn(),
  searchAssets: vi.fn(),
  renameAsset: vi.fn(),
  setAssetPurchaseDate: vi.fn(),
  setAssetValues: vi.fn(),
  changeAssetCondition: vi.fn(),
  changeAssetLifecycle: vi.fn(),
  moveAsset: vi.fn(),
  setAssetSealable: vi.fn(),
  applyAssetSeal: vi.fn(),
  breakAssetSeal: vi.fn(),
  openAssetRepair: vi.fn(),
  closeAssetRepair: vi.fn(),
  createAssetReplacement: vi.fn(),
  getPackingContents: vi.fn(),
}));
const identity = vi.hoisted(() => ({
  role: "OWNER",
  principal: { userId: "user-1", organizationId: "org-1" },
}));
vi.mock("../identity/useSession", () => ({ useSession: () => identity }));
vi.mock("./inventoryApi", () => ({ ...api, errorMessage: (error: Error) => error.message }));
vi.mock("./MediaPanel", () => ({
  MediaPanel: ({ canManage }: { canManage: boolean }) => (
    <div>Photos {canManage ? "editable" : "read only"}</div>
  ),
}));
vi.mock("./PackingPanel", () => ({ PackingPanel: () => <div>Packing administration</div> }));
vi.mock("./AssetLabelExportDialog", () => ({
  AssetLabelExportDialog: () => <div role="dialog" aria-label="Export asset labels" />,
}));

const asset = {
  id: "asset-1",
  assetModelId: "model-1",
  assetModelName: "Network case",
  displayName: "Main network case",
  individualName: "Main network case",
  publicCode: "7K3MXY",
  unitNumber: 1,
  condition: "GOOD",
  lifecycleState: "ACTIVE",
  archived: false,
  metadataIncomplete: false,
  sealable: true,
  sealState: "INVALIDATED",
  values: [],
  purchaseDate: "2026-01-02",
} as unknown as AssetRecord;
const placement = {
  assetId: asset.id,
  directLocationId: "",
  parentContainerAssetId: "parent",
  version: 7,
  effectivePath: ["Store", "Parent case", asset.displayName],
  effectivePathText: "Store / Parent case / Main network case",
} as AssetPlacementRecord;
const emptyContents = {
  containerAssetId: asset.id,
  complete: true,
  totalAssetCount: 0,
  matchedCount: 0,
  extraCount: 0,
  misplacedCount: 0,
  inactiveCount: 0,
  totalRequirementCount: 0,
  totalConsumableCount: 0,
  requirements: [],
  assets: [],
  consumables: [],
  nextCursor: null,
};
const ok = (data: unknown) => ({ kind: "ok", data });
function page() {
  return (
    <MemoryRouter initialEntries={["/inventory/assets/asset-1"]}>
      <Routes>
        <Route path="/inventory/assets/:assetId" element={<AssetPage />} />
      </Routes>
    </MemoryRouter>
  );
}
async function edit(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("button", { name: "Asset actions" }));
  await user.click(screen.getByRole("menuitem", { name: "Edit" }));
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

beforeEach(() => {
  vi.resetAllMocks();
  identity.role = "OWNER";
  identity.principal.userId = "user-1";
  api.getAsset.mockResolvedValue(ok(asset));
  api.getAssetModel.mockResolvedValue(
    ok({
      id: "model-1",
      canContainAssets: true,
      description: "CAT6 patch cables\n\nRed and blue.\n1 metre",
    }),
  );
  api.getAssetPlacement.mockResolvedValue(ok(placement));
  api.listAssetRepairs.mockResolvedValue(ok([]));
  api.listAssetHistory.mockResolvedValue(ok([]));
  api.listSealHistory.mockResolvedValue(ok([]));
  api.listCustomFields.mockResolvedValue(ok([]));
  api.listLocations.mockResolvedValue(ok([]));
  api.searchAssets.mockResolvedValue(ok({ items: [], nextCursor: null }));
  api.getPackingContents.mockResolvedValue(ok(emptyContents));
  api.renameAsset.mockResolvedValue(ok(asset));
  api.setAssetPurchaseDate.mockResolvedValue(ok(asset));
  api.moveAsset.mockResolvedValue(ok(placement));
});

describe("asset detail surface", () => {
  it("opens read only with aligned identity, description, linked parent and no editing controls", async () => {
    render(page());
    await screen.findByRole("heading", { name: asset.displayName });
    expect(screen.getByText("CAT6 patch cables, Red and blue. 1 metre")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Parent case" })).toHaveAttribute(
      "href",
      "/inventory/assets/parent",
    );
    expect(screen.getByText("Invalidated - verification required")).toBeInTheDocument();
    expect(screen.getByText("Photos read only")).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.queryByText("Packing administration")).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Asset actions" }).parentElement?.parentElement,
    ).toHaveTextContent(asset.publicCode);
    expect(screen.getByRole("button", { name: "Download packing sheet" })).toBeVisible();
  });
  it("keeps a Viewer label reprint and read-only history without repair mutation controls", async () => {
    identity.role = "VIEWER";
    api.getAssetModel.mockResolvedValue(ok({ canContainAssets: false }));
    api.listAssetRepairs.mockResolvedValue(
      ok([
        {
          id: "repair",
          referenceOrDescription: "Broken socket",
          openedAt: "2026-01-01T10:00:00Z",
          closedAt: null,
        },
      ]),
    );
    const user = userEvent.setup();
    render(page());
    await user.click(await screen.findByRole("button", { name: "Export label" }));
    expect(screen.getByRole("dialog", { name: "Export asset labels" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Asset actions" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "View history" }));
    const history = screen.getByRole("dialog", { name: "History" });
    expect(within(history).getByText("Broken socket")).toBeInTheDocument();
    expect(within(history).queryByRole("combobox")).not.toBeInTheDocument();
    expect(within(history).queryByRole("button", { name: "Close repair" })).not.toBeInTheDocument();
  });
  it("retains failed section drafts, refreshes successful saves and discards unsaved edits on Cancel", async () => {
    const user = userEvent.setup();
    render(page());
    await edit(user);
    expect(screen.getByText("Photos editable")).toBeInTheDocument();
    expect(screen.getByText("Packing administration")).toBeInTheDocument();
    const name = screen.getByRole("textbox", { name: "Individual name (optional)" });
    await user.clear(name);
    await user.paste("New case name");
    api.renameAsset.mockResolvedValueOnce({
      kind: "error",
      error: AppError.network(new Error("offline")),
    });
    await user.click(screen.getByRole("button", { name: "Save name" }));
    await screen.findByRole("alert");
    expect(name).toHaveValue("New case name");
    api.getAsset.mockResolvedValue(
      ok({ ...asset, individualName: "New case name", displayName: "New case name" }),
    );
    await user.click(screen.getByRole("button", { name: "Save name" }));
    await screen.findByRole("heading", { name: "New case name" });
    await user.clear(name);
    await user.paste("Unsaved text");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    await edit(user);
    expect(screen.getByRole("textbox", { name: "Individual name (optional)" })).toHaveValue(
      "New case name",
    );
  });
  it("requires archive confirmation and refreshes only after the command succeeds", async () => {
    const user = userEvent.setup();
    render(page());
    await user.click(await screen.findByRole("button", { name: "Asset actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Archive" }));
    expect(api.setAssetArchived).not.toHaveBeenCalled();
    const dialog = screen.getByRole("dialog", { name: "Archive asset?" });
    await user.click(within(dialog).getByRole("button", { name: "Archive asset" }));
    expect(api.setAssetArchived).toHaveBeenCalledWith(asset.id, true);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });
  it("opens repairs in a dialog and updates the normal repair state on success", async () => {
    const user = userEvent.setup();
    render(page());
    await user.click(await screen.findByRole("button", { name: "Asset actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Open repair" }));
    const dialog = screen.getByRole("dialog", { name: "Open repair" });
    await user.type(within(dialog).getByRole("textbox"), "Socket damaged");
    api.openAssetRepair.mockResolvedValue(ok({ id: "repair" }));
    api.listAssetRepairs.mockResolvedValue(
      ok([{ id: "repair", referenceOrDescription: "Socket damaged", closedAt: null }]),
    );
    await user.click(within(dialog).getByRole("button", { name: "Open repair" }));
    await screen.findByText("In repair");
    expect(api.openAssetRepair).toHaveBeenCalledWith(asset.id, "Socket damaged");
  });
  it("keeps repair closing and history in the management dialog", async () => {
    const user = userEvent.setup();
    const repair = {
      id: "repair",
      referenceOrDescription: "Socket repair",
      openedAt: "2026-01-01T10:00:00Z",
      closedAt: null,
    };
    api.listAssetRepairs.mockResolvedValue(ok([repair]));
    api.closeAssetRepair.mockResolvedValue(
      ok({ ...repair, closedAt: "2026-01-02T10:00:00Z", resultingCondition: "GOOD" }),
    );
    render(page());
    await edit(user);
    await user.click(screen.getByRole("button", { name: "Repairs and history" }));
    const dialog = screen.getByRole("dialog", { name: "Repairs and history" });
    api.listAssetRepairs.mockResolvedValue(
      ok([{ ...repair, closedAt: "2026-01-02T10:00:00Z", resultingCondition: "GOOD" }]),
    );
    await user.click(within(dialog).getByRole("button", { name: "Close repair" }));
    expect(api.closeAssetRepair).toHaveBeenCalledWith("repair", "GOOD");
    await waitFor(() =>
      expect(
        within(dialog).queryByRole("button", { name: "Close repair" }),
      ).not.toBeInTheDocument(),
    );
    expect(within(dialog).getByText(/Closed/)).toBeInTheDocument();
  });
  it("does not restore an old editing response after an identity switch", async () => {
    const user = userEvent.setup();
    const view = render(page());
    await edit(user);
    const pending = deferred<unknown>();
    api.renameAsset.mockReturnValueOnce(pending.promise);
    await user.click(screen.getByRole("button", { name: "Save name" }));
    identity.principal.userId = "user-2";
    view.rerender(page());
    await screen.findByRole("heading", { name: asset.displayName });
    await act(async () => {
      pending.resolve({ kind: "error", error: new Error("Old account failure") });
    });
    expect(screen.queryByText("Old account failure")).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });
});

describe("versioned editor and contents", () => {
  it("does not adopt a newer remote placement version while saving an older draft", async () => {
    const user = userEvent.setup();
    const props = {
      asset,
      placement,
      containerCapable: true,
      onSaved: vi.fn().mockResolvedValue(undefined),
    };
    const view = render(
      <MemoryRouter>
        <AssetDetailsEditor {...props} />
      </MemoryRouter>,
    );
    await screen.findByRole("button", { name: "Save placement" });
    view.rerender(
      <MemoryRouter>
        <AssetDetailsEditor
          {...props}
          placement={{ ...placement, version: 8, parentContainerAssetId: "remote-parent" }}
        />
      </MemoryRouter>,
    );
    api.moveAsset.mockResolvedValue({ kind: "error", error: new Error("Refresh placement") });
    await user.click(screen.getByRole("button", { name: "Save placement" }));
    expect(api.moveAsset).toHaveBeenCalledWith(asset.id, {
      locationId: undefined,
      parentContainerAssetId: "parent",
      expectedVersion: 7,
    });
    expect(api.getAssetPlacement).not.toHaveBeenCalled();
    await screen.findByText("Refresh placement");
    expect(screen.getByText(/Current parent:/)).toHaveTextContent("Parent case");
  });
  it("keeps the original seal draft version and never claims manual verification", async () => {
    const user = userEvent.setup();
    const props = {
      asset,
      placement,
      containerCapable: true,
      onSaved: vi.fn().mockResolvedValue(undefined),
    };
    const view = render(
      <MemoryRouter>
        <AssetDetailsEditor {...props} />
      </MemoryRouter>,
    );
    await user.click(screen.getByRole("combobox", { name: "Physical seal state" }));
    expect(screen.queryByRole("option", { name: "Verified" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "Applied" }));
    view.rerender(
      <MemoryRouter>
        <AssetDetailsEditor {...props} placement={{ ...placement, version: 8 }} />
      </MemoryRouter>,
    );
    api.applyAssetSeal.mockResolvedValue(new Error("Seal changed; reload"));
    await user.click(screen.getByRole("button", { name: "Save physical seal" }));
    expect(api.applyAssetSeal).toHaveBeenCalledWith(asset.id, { expectedVersion: 7 });
    expect(api.getAssetPlacement).not.toHaveBeenCalled();
    await screen.findByText("Seal changed; reload");
    expect(screen.getByRole("combobox", { name: "Physical seal state" })).toHaveTextContent(
      "Applied",
    );
  });
  it("groups authoritative assignments, keeps inactive problems and missing quantities, and loads all codes", async () => {
    const named = (id: string) => ({
      assetId: id,
      publicCode: id,
      displayName: "Cable " + id,
      assetModelId: "cable",
      assetModelName: "Cable",
      active: true,
    });
    api.getPackingContents
      .mockResolvedValueOnce(
        ok({
          ...emptyContents,
          totalAssetCount: 3,
          totalRequirementCount: 1,
          matchedCount: 2,
          inactiveCount: 1,
          complete: false,
          nextCursor: "page2",
          assets: [
            { asset: named("inactive"), status: "INACTIVE" },
            { asset: named("code1"), status: "MATCHED", requirementId: "req" },
          ],
          requirements: [
            {
              id: "req",
              type: "MODEL_QUANTITY",
              assetModelName: "Cable",
              requiredQuantity: 3,
              presentQuantity: 2,
              missingQuantity: 1,
            },
          ],
        }),
      )
      .mockResolvedValueOnce(
        ok({
          ...emptyContents,
          totalAssetCount: 3,
          requirements: [],
          assets: [{ asset: named("code2"), status: "MATCHED", requirementId: "req" }],
        }),
      );
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <AssetContentsCard assetId={asset.id} revision={0} />
      </MemoryRouter>,
    );
    await user.click(await screen.findByRole("button", { name: /2.*Cable.*1 missing/ }));
    expect(screen.getByRole("link", { name: /code1/ })).toBeInTheDocument();
    expect(screen.getByText("Inactive")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Show more contents and requirements" }));
    await screen.findByRole("link", { name: /code2/ });
    expect(api.getPackingContents).toHaveBeenLastCalledWith(asset.id, "page2");
  });
  it("rejects stale page mixing and offers a fresh reload", async () => {
    api.getPackingContents
      .mockResolvedValueOnce(
        ok({
          ...emptyContents,
          nextCursor: "old",
          assets: [
            {
              asset: { assetId: "old", displayName: "Old item", publicCode: "OLD" },
              status: "EXTRA",
            },
          ],
        }),
      )
      .mockResolvedValueOnce({
        kind: "error",
        error: AppError.fromProblemDetails(
          { type: "test", title: "Contents changed", status: 409 },
          409,
        ),
      });
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <AssetContentsCard assetId={asset.id} revision={0} />
      </MemoryRouter>,
    );
    await user.click(
      await screen.findByRole("button", { name: "Show more contents and requirements" }),
    );
    await screen.findByText("Contents changed");
    expect(screen.queryByRole("link", { name: /Old item/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Refresh" }));
    await screen.findByText("No direct contents.");
    expect(api.getPackingContents).toHaveBeenLastCalledWith(asset.id, undefined);
  });
});
