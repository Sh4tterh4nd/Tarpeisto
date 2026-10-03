import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppError } from "@tarpeisto/api-client";
import { AssetPackingDetailsEditor } from "./AssetPackingDetailsEditor";
import { packingHeaderTextColor } from "./packingHeaderColor";
import type { AssetRecord } from "./inventoryApi";
const api = vi.hoisted(() => ({ getAsset: vi.fn(), setAssetPackingDetails: vi.fn() }));
vi.mock("./inventoryApi", () => ({ ...api, errorMessage: (error: Error) => error.message }));
const asset = {
  id: "asset-1",
  displayName: "Network case",
  assetModelName: "Case",
  containerColor: "#FFFFFF",
  unitDescription: undefined,
  version: 3,
} as AssetRecord;
const ok = (data: unknown) => ({ kind: "ok", data });
beforeEach(() => {
  vi.resetAllMocks();
  api.getAsset.mockResolvedValue(ok(asset));
  api.setAssetPackingDetails.mockImplementation(async (_id, body) =>
    ok({ ...asset, ...body, version: body.expectedVersion + 1 }),
  );
});
describe("AssetPackingDetailsEditor", () => {
  it("defaults to white and applies the same strict brightness threshold as the PDF", () => {
    render(<AssetPackingDetailsEditor asset={asset} onSaved={vi.fn()} />);
    expect(screen.getByLabelText("Packing header color")).toHaveValue("#FFFFFF");
    expect(packingHeaderTextColor("#0078A5")).toBe("#000000");
    expect(packingHeaderTextColor("#0078A4")).toBe("#FFFFFF");
  });
  it("uses the fresh global version after an own name/date save when packing values still match", async () => {
    const user = userEvent.setup(),
      onSaved = vi.fn();
    render(<AssetPackingDetailsEditor asset={asset} onSaved={onSaved} />);
    api.getAsset.mockResolvedValue(ok({ ...asset, displayName: "Renamed case", version: 9 }));
    await user.clear(screen.getByLabelText("Packing header color"));
    await user.type(screen.getByLabelText("Packing header color"), "1122aa");
    await user.type(screen.getByLabelText("Container description"), "Dedicated cable set");
    await user.click(screen.getByRole("button", { name: "Save packing details" }));
    await waitFor(() =>
      expect(api.setAssetPackingDetails).toHaveBeenCalledWith(asset.id, {
        containerColor: "#1122AA",
        unitDescription: "Dedicated cable set",
        expectedVersion: 9,
      }),
    );
    expect(await screen.findByText("Packing details saved.")).toBeInTheDocument();
    expect(onSaved).toHaveBeenCalledTimes(1);
    api.getAsset.mockResolvedValue(
      ok({
        ...asset,
        containerColor: "#1122AA",
        unitDescription: "Dedicated cable set",
        version: 10,
      }),
    );
    await user.click(screen.getByRole("button", { name: "Save packing details" }));
    await waitFor(() => expect(api.setAssetPackingDetails).toHaveBeenCalledTimes(2));
  });
  it("retains the local draft on remote conflict and requires explicit reload before writing", async () => {
    const user = userEvent.setup();
    render(<AssetPackingDetailsEditor asset={asset} onSaved={vi.fn()} />);
    await user.type(screen.getByLabelText("Container description"), "My local instructions");
    api.getAsset.mockResolvedValue(
      ok({
        ...asset,
        containerColor: "#112233",
        unitDescription: "Remote instructions",
        version: 5,
      }),
    );
    await user.click(screen.getByRole("button", { name: "Save packing details" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("changed elsewhere");
    expect(api.setAssetPackingDetails).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Container description")).toHaveValue("My local instructions");
    expect(screen.getByRole("button", { name: "Save packing details" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Reload packing details" }));
    await waitFor(() =>
      expect(screen.getByLabelText("Container description")).toHaveValue("Remote instructions"),
    );
    await user.type(screen.getByLabelText("Container description"), " reviewed");
    await user.click(screen.getByRole("button", { name: "Save packing details" }));
    await waitFor(() =>
      expect(api.setAssetPackingDetails).toHaveBeenCalledWith(asset.id, {
        containerColor: "#112233",
        unitDescription: "Remote instructions reviewed",
        expectedVersion: 5,
      }),
    );
  });
  it("keeps input after write failure and prevents blind retry after a post-read409", async () => {
    const user = userEvent.setup();
    render(<AssetPackingDetailsEditor asset={asset} onSaved={vi.fn()} />);
    await user.type(screen.getByLabelText("Container description"), "Keep this draft");
    api.setAssetPackingDetails.mockResolvedValue({
      kind: "error",
      error: AppError.fromProblemDetails(
        {
          type: "test",
          title: "Stale packing details",
          status: 409,
          detail: "Stale packing details",
        },
        409,
      ),
    });
    await user.click(screen.getByRole("button", { name: "Save packing details" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Stale packing details");
    expect(screen.getByLabelText("Container description")).toHaveValue("Keep this draft");
    expect(screen.getByRole("button", { name: "Save packing details" })).toBeDisabled();
  });
  it("does not write or refresh after the actor/route surface unmounts during its fresh read", async () => {
    let resolve!: (value: unknown) => void;
    api.getAsset.mockReturnValue(
      new Promise((r) => {
        resolve = r;
      }),
    );
    const user = userEvent.setup(),
      onSaved = vi.fn();
    const rendered = render(<AssetPackingDetailsEditor asset={asset} onSaved={onSaved} />);
    await user.click(screen.getByRole("button", { name: "Save packing details" }));
    rendered.unmount();
    await act(async () => {
      resolve(ok({ ...asset, version: 8 }));
    });
    expect(api.setAssetPackingDetails).not.toHaveBeenCalled();
    expect(onSaved).not.toHaveBeenCalled();
  });
});
