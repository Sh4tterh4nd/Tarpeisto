import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AssetsPage } from "./AssetsPage";

const api = vi.hoisted(() => ({ listCategories: vi.fn(), searchAssets: vi.fn() }));
vi.mock("./inventoryApi", () => ({ ...api, errorMessage: (error: Error) => error.message }));

const ok = (data: unknown) => ({ kind: "ok" as const, data });

describe("AssetsPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.listCategories.mockResolvedValue(
      ok([{ id: "lighting", name: "Lighting", color: "#123456", archived: false }]),
    );
    api.searchAssets.mockImplementation((query) =>
      Promise.resolve(
        ok({
          items: query.cursor
            ? [
                {
                  id: "asset-2",
                  displayName: "Flight case",
                  publicCode: "CASE01",
                  assetModelId: "model-2",
                  assetModelName: "Case",
                  categoryId: undefined,
                  categoryName: "Default",
                  categoryColor: "#5B6472",
                  canContainAssets: true,
                  condition: "GOOD",
                  lifecycleState: "ACTIVE",
                  archived: false,
                  parentContainerAssetId: undefined,
                  placementVersion: 2,
                },
              ]
            : [
                {
                  id: "asset-1",
                  displayName: "LED panel",
                  publicCode: "LED001",
                  assetModelId: "model-1",
                  assetModelName: "Panel",
                  categoryId: "lighting",
                  categoryName: "Lighting",
                  categoryColor: "#123456",
                  canContainAssets: false,
                  condition: "GOOD",
                  lifecycleState: "ACTIVE",
                  archived: false,
                  parentContainerAssetId: undefined,
                  placementVersion: 1,
                },
              ],
          nextCursor: query.cursor ? undefined : "next-page",
        }),
      ),
    );
  });

  it("filters, sorts, and loads another accessible asset page", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <AssetsPage />
      </MemoryRouter>,
    );
    expect(await screen.findByRole("link", { name: "LED panel" })).toHaveAttribute(
      "href",
      "/inventory/assets/asset-1",
    );
    expect(screen.getByText("Lighting")).toBeInTheDocument();
    await user.click(screen.getByRole("combobox", { name: "Category" }));
    await user.click(screen.getByRole("option", { name: "Default" }));
    await waitFor(() =>
      expect(api.searchAssets).toHaveBeenLastCalledWith(
        expect.objectContaining({ category: "Default" }),
      ),
    );
    await user.click(screen.getByRole("button", { name: "Sort by state" }));
    await waitFor(() =>
      expect(api.searchAssets).toHaveBeenLastCalledWith(
        expect.objectContaining({ sort: "lifecycle", direction: "asc" }),
      ),
    );
    await user.click(screen.getByRole("button", { name: "Load more" }));
    await waitFor(() =>
      expect(api.searchAssets).toHaveBeenLastCalledWith(
        expect.objectContaining({ cursor: "next-page" }),
      ),
    );
    expect(await screen.findByRole("link", { name: "Flight case" })).toBeInTheDocument();
  });

  it("ignores a late response from the previous asset filter", async () => {
    let resolvePrevious: (value: unknown) => void = () => {};
    api.searchAssets.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolvePrevious = resolve;
        }),
    );
    api.searchAssets.mockResolvedValue(ok({ items: [], nextCursor: undefined }));
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <AssetsPage />
      </MemoryRouter>,
    );
    await waitFor(() => expect(api.searchAssets).toHaveBeenCalledTimes(1));
    await user.type(screen.getByRole("textbox", { name: "Search assets" }), "camera");
    await waitFor(() =>
      expect(api.searchAssets).toHaveBeenLastCalledWith(
        expect.objectContaining({ query: "camera" }),
      ),
    );
    await screen.findByText("No assets match these filters.");
    await act(async () =>
      resolvePrevious(
        ok({ items: [{ id: "old", displayName: "Old result" }], nextCursor: "old-page" }),
      ),
    );
    expect(screen.queryByRole("link", { name: "Old result" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
  });
});
