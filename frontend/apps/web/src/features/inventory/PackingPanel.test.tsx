import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PackingPanel } from "./PackingPanel";

const api = vi.hoisted(() => ({
  addPackingRequirement: vi.fn(),
  addPackingTemplateRequirement: vi.fn(),
  applyPackingTemplate: vi.fn(),
  archivePackingRequirement: vi.fn(),
  createPackingTemplate: vi.fn(),
  listAssetModels: vi.fn(),
  getAsset: vi.fn(),
  searchAssets: vi.fn(),
  listPackingRequirements: vi.fn(),
  listPackingTemplates: vi.fn(),
  previewPacking: vi.fn(),
  restorePackingRequirement: vi.fn(),
  setPackingTemplateArchived: vi.fn(),
  setPackingTemplateRequirementArchived: vi.fn(),
  updatePackingRequirement: vi.fn(),
  updatePackingTemplate: vi.fn(),
  updatePackingTemplateRequirement: vi.fn(),
}));
const packingSheet = vi.hoisted(() => ({
  downloadPackingSheet: vi.fn(),
  savePackingSheet: vi.fn(),
}));

vi.mock("./inventoryApi", () => ({ ...api, errorMessage: (error: Error) => error.message }));
vi.mock("./packingSheetApi", () => packingSheet);

const modelRequirement = {
  id: "cable-requirement",
  type: "MODEL_QUANTITY",
  assetModelId: "cable-model",
  specificAssetId: undefined,
  requiredQuantity: 5,
  displayOrder: 0,
  archived: false,
  version: 3,
};
const archivedRequirement = {
  ...modelRequirement,
  id: "archived-requirement",
  archived: true,
  version: 4,
};
const consumableRequirement = {
  id: "tape-requirement",
  type: "CONSUMABLE_QUANTITY",
  assetModelId: "tape-model",
  specificAssetId: undefined,
  requiredQuantity: 2,
  displayOrder: 1,
  archived: false,
  version: 5,
};
const template = {
  id: "template-1",
  name: "Network box",
  description: "A copied starter set",
  archived: false,
  version: 2,
  requirements: [{ ...modelRequirement, id: "template-requirement", version: 1 }],
};

function ok(data: unknown) {
  return { kind: "ok" as const, data };
}

async function expandPacking(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("button", { name: /Packing sheet/ }));
}

describe("PackingPanel", () => {
  let currentRequirements: (typeof modelRequirement)[];

  beforeEach(() => {
    vi.clearAllMocks();
    currentRequirements = [modelRequirement, archivedRequirement, consumableRequirement];
    api.listAssetModels.mockResolvedValue(
      ok([
        {
          id: "cable-model",
          name: "Numbered cable",
          trackingMode: "SERIALIZED_ASSET",
          archived: false,
        },
        { id: "tape-model", name: "Gaffer tape", trackingMode: "QUANTITY_STOCK", archived: false },
      ]),
    );
    api.listPackingRequirements.mockImplementation(() => Promise.resolve(ok(currentRequirements)));
    api.listPackingTemplates.mockResolvedValue(ok([template]));
    api.searchAssets.mockResolvedValue(
      ok({
        items: [
          {
            id: "asset-1",
            displayName: "Access point 1",
            publicCode: "AP-001",
            assetModelName: "Access point",
            parentContainerAssetId: undefined,
            placementVersion: 8,
          },
        ],
        nextCursor: undefined,
      }),
    );
    api.previewPacking.mockImplementation((_containerAssetId, observedConsumableQuantities = {}) =>
      Promise.resolve(
        ok({
          complete: false,
          satisfiedRequirementIds: [],
          missingRequirementIds: ["cable-requirement", "tape-requirement"],
          extraAssetIds: ["extra-asset"],
          misplacedAssetIds: ["pinned-elsewhere"],
          consumables: currentRequirements.some(
            (requirement) => requirement.id === "tape-requirement" && !requirement.archived,
          )
            ? [
                {
                  requirementId: "tape-requirement",
                  assetModelId: "tape-model",
                  requiredQuantity: 2,
                  observedQuantity: observedConsumableQuantities["tape-requirement"] ?? 1,
                  satisfied: false,
                },
              ]
            : [],
        }),
      ),
    );
    api.updatePackingRequirement.mockResolvedValue(ok(modelRequirement));
    api.restorePackingRequirement.mockResolvedValue(
      ok({ ...archivedRequirement, archived: false }),
    );
    api.createPackingTemplate.mockResolvedValue(
      ok({ ...template, id: "template-2", name: "Cable box" }),
    );
    api.addPackingTemplateRequirement.mockResolvedValue(ok(template));
    api.applyPackingTemplate.mockResolvedValue(ok([modelRequirement]));
    api.setPackingTemplateArchived.mockResolvedValue(ok(template));
    api.setPackingTemplateRequirementArchived.mockResolvedValue(ok(template));
    packingSheet.downloadPackingSheet.mockResolvedValue({
      kind: "ok",
      data: new Blob(["packing-sheet"], { type: "application/pdf" }),
    });
  });

  it("groups packing state and sends observed consumables into a refreshed preview", async () => {
    const user = userEvent.setup();
    render(<PackingPanel containerAssetId="container-1" canManage />);
    await expandPacking(user);

    expect(
      await screen.findByRole("heading", { name: "Interchangeable serialized" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Packing needs attention: 2 missing requirements. Extra: extra-asset. Misplaced: pinned-elsewhere.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText("Required: 5 · Missing")).toBeInTheDocument();
    const observed = screen.getByLabelText("Observed Gaffer tape");
    await user.clear(observed);
    await user.type(observed, "2.5");
    await user.click(screen.getByRole("button", { name: "Refresh preview" }));

    await waitFor(() =>
      expect(api.previewPacking).toHaveBeenLastCalledWith("container-1", {
        "tape-requirement": 2.5,
      }),
    );
  });

  it("keeps manager actions hidden from a viewer", async () => {
    const user = userEvent.setup();
    render(<PackingPanel containerAssetId="container-1" canManage={false} />);
    await expandPacking(user);

    expect(await screen.findByText("Required: 5 · Missing")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Manage templates" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add requirement" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit" })).not.toBeInTheDocument();
  });

  it("lets every permanent role download a packing sheet and offers retry after failure", async () => {
    const user = userEvent.setup();
    render(<PackingPanel containerAssetId="container-1" canManage={false} />);
    await expandPacking(user);
    await screen.findByRole("heading", { name: "Interchangeable serialized" });
    await user.click(screen.getByRole("button", { name: "Download packing sheet" }));
    await waitFor(() =>
      expect(packingSheet.downloadPackingSheet).toHaveBeenCalledWith("container-1"),
    );
    expect(packingSheet.savePackingSheet).toHaveBeenCalledWith(expect.any(Blob), "container-1");

    packingSheet.downloadPackingSheet.mockResolvedValueOnce({
      kind: "error",
      error: new Error("Network unavailable"),
    });
    await user.click(screen.getByRole("button", { name: "Download packing sheet" }));
    expect(await screen.findByText(/Could not download the packing sheet/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(packingSheet.downloadPackingSheet).toHaveBeenCalledTimes(3));
  });

  it("prunes a removed consumable observation before the next preview", async () => {
    const user = userEvent.setup();
    render(<PackingPanel containerAssetId="container-1" canManage />);
    await expandPacking(user);
    const observed = await screen.findByLabelText("Observed Gaffer tape");
    await user.clear(observed);
    await user.type(observed, "2.5");
    await user.click(screen.getByRole("button", { name: "Refresh preview" }));
    await waitFor(() =>
      expect(api.previewPacking).toHaveBeenLastCalledWith("container-1", {
        "tape-requirement": 2.5,
      }),
    );

    api.archivePackingRequirement.mockImplementationOnce(async () => {
      currentRequirements = currentRequirements.map((requirement) =>
        requirement.id === "tape-requirement" ? { ...requirement, archived: true } : requirement,
      );
      return undefined;
    });
    const consumables = screen.getByLabelText("Consumables");
    await user.click(within(consumables).getByRole("button", { name: "Archive" }));

    await waitFor(() =>
      expect(api.archivePackingRequirement).toHaveBeenCalledWith("tape-requirement", 5),
    );
    await waitFor(() => expect(api.previewPacking).toHaveBeenLastCalledWith("container-1", {}));
    expect(screen.queryByLabelText("Observed Gaffer tape")).not.toBeInTheDocument();
  });

  it("updates a requirement with an exact reference and always submits quantity one, then restores it", async () => {
    const user = userEvent.setup();
    render(<PackingPanel containerAssetId="container-1" canManage />);
    await expandPacking(user);
    await screen.findByText("Required: 5 · Missing");

    const firstEdit = screen.getAllByRole("button", { name: "Edit" })[0];
    expect(firstEdit).toBeDefined();
    await user.click(firstEdit!);
    await user.click(screen.getByRole("combobox", { name: "Requirement kind" }));
    await user.click(screen.getByRole("option", { name: "Exact asset" }));
    const picker = screen.getByRole("combobox", { name: "Exact asset" });
    await user.type(picker, "AP");
    await user.click(await screen.findByRole("option", { name: /Access point 1/ }));
    expect(screen.getByRole("spinbutton", { name: "Required quantity" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(api.updatePackingRequirement).toHaveBeenCalledWith("cable-requirement", {
        expectedVersion: 3,
        requirement: {
          type: "SPECIFIC_ASSET",
          assetModelId: undefined,
          specificAssetId: "asset-1",
          specificAssetReference: undefined,
          requiredQuantity: 1,
          assignToContainer: false,
          expectedAssetVersion: undefined,
        },
      }),
    );
    await waitFor(() =>
      expect(
        screen.queryByRole("dialog", { name: "Add packing requirement" }),
      ).not.toBeInTheDocument(),
    );
    await user.click(screen.getByRole("button", { name: "Restore" }));
    await waitFor(() =>
      expect(api.restorePackingRequirement).toHaveBeenCalledWith("archived-requirement", 4),
    );
  });

  it("assigns a selected exact asset only when explicitly checked and refreshes contents after success", async () => {
    const user = userEvent.setup();
    const onContentsChanged = vi.fn();
    api.addPackingRequirement.mockResolvedValue(ok({}));
    render(
      <PackingPanel
        containerAssetId="container-1"
        canManage
        onContentsChanged={onContentsChanged}
      />,
    );
    await expandPacking(user);
    await user.click(screen.getByRole("button", { name: "Add requirement" }));
    await user.click(screen.getByRole("combobox", { name: "Requirement kind" }));
    await user.click(screen.getByRole("option", { name: "Exact asset" }));
    const checkbox = screen.getByRole("checkbox", {
      name: "Assign this asset to this container now",
    });
    expect(checkbox).not.toBeChecked();
    await user.type(screen.getByRole("combobox", { name: "Exact asset" }), "AP");
    await user.click(await screen.findByRole("option", { name: /Access point 1/ }));
    await user.click(checkbox);
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(api.addPackingRequirement).toHaveBeenCalledWith(
        "container-1",
        expect.objectContaining({
          specificAssetId: "asset-1",
          assignToContainer: true,
          expectedAssetVersion: 8,
        }),
      ),
    );
    await waitFor(() => expect(onContentsChanged).toHaveBeenCalledTimes(1));
  });

  it("loads the existing exact asset into the picker when editing a requirement", async () => {
    api.listPackingRequirements.mockResolvedValue(
      ok([
        {
          ...modelRequirement,
          type: "SPECIFIC_ASSET",
          assetModelId: undefined,
          specificAssetId: "asset-1",
          requiredQuantity: 1,
        },
      ]),
    );
    api.getAsset.mockResolvedValue(ok({ id: "asset-1", publicCode: "AP-001" }));
    const user = userEvent.setup();
    render(<PackingPanel containerAssetId="container-1" canManage />);
    await expandPacking(user);
    await user.click(await screen.findByRole("button", { name: "Edit" }));
    await waitFor(() =>
      expect(screen.getByRole("combobox", { name: "Exact asset" })).toHaveDisplayValue(
        /Access point 1.*AP-001/,
      ),
    );
    expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
    expect(
      screen.getByRole("checkbox", { name: "Assign this asset to this container now" }),
    ).not.toBeChecked();
  });

  it("manages copied templates and their independent requirements", async () => {
    const user = userEvent.setup();
    render(<PackingPanel containerAssetId="container-1" canManage />);
    await expandPacking(user);
    await screen.findByText("Required: 5 · Missing");
    expect(
      screen.getByText(
        "This container keeps its own requirements. Templates are copied, never live-linked.",
      ),
    ).toBeInTheDocument();

    await user.click(screen.getByRole("combobox", { name: "Copy template onto this container" }));
    await user.click(screen.getByRole("option", { name: "Network box" }));
    await user.click(screen.getByRole("button", { name: "Copy requirements" }));
    await waitFor(() =>
      expect(api.applyPackingTemplate).toHaveBeenCalledWith("container-1", "template-1"),
    );

    await user.click(screen.getByRole("button", { name: "Manage templates" }));
    const library = screen.getByRole("dialog", { name: "Manage packing templates" });
    expect(within(library).getByText("A copied starter set")).toBeInTheDocument();
    expect(
      within(library).getByRole("button", { name: "Add template requirement" }),
    ).toBeInTheDocument();
    await user.click(within(library).getByRole("button", { name: "New template" }));
    const createDialog = screen.getByRole("dialog", { name: "Create packing template" });
    await user.type(
      within(createDialog).getByRole("textbox", { name: "Template name" }),
      "Cable box",
    );
    await user.click(within(createDialog).getByRole("button", { name: "Create template" }));
    await waitFor(() =>
      expect(api.createPackingTemplate).toHaveBeenCalledWith({
        name: "Cable box",
        description: undefined,
      }),
    );
  });
});
