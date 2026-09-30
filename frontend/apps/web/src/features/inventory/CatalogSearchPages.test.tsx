import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, expect, it, vi } from "vitest";
import { AppError } from "@tarpeisto/api-client";
import { ModelSearchPage } from "./ModelSearchPage";
import { StockSearchPage } from "./StockSearchPage";
const api = vi.hoisted(() => ({ searchModels: vi.fn(), searchStock: vi.fn() }));
vi.mock("./catalogSearchApi", () => api);
vi.mock("./inventoryApi", () => ({
  listCategories: vi.fn().mockResolvedValue({ kind: "ok", data: [] }),
  listLocations: vi.fn().mockResolvedValue({ kind: "ok", data: [] }),
}));
vi.mock("../identity/useSession", () => ({ useSession: () => ({ role: "VIEWER" }) }));
const model = {
  id: "model",
  name: "Historical model",
  categoryName: "Default",
  trackingMode: "SERIALIZED_ASSET",
  archived: true,
};
beforeEach(() => vi.clearAllMocks());
it("model headers request descending server order and failed changed filters hide old rows and cursor", async () => {
  const user = userEvent.setup();
  api.searchModels.mockResolvedValue({
    kind: "ok",
    data: { items: [model], nextCursor: "old-cursor" },
  });
  render(
    <MemoryRouter>
      <ModelSearchPage />
    </MemoryRouter>,
  );
  expect(await screen.findByRole("link", { name: "Historical model" })).toBeVisible();
  await user.click(screen.getByRole("button", { name: "Sort by name" }));
  await waitFor(() =>
    expect(api.searchModels).toHaveBeenLastCalledWith(
      expect.objectContaining({ sort: "name", direction: "desc", cursor: undefined }),
    ),
  );
  expect(await screen.findByRole("link", { name: "Historical model" })).toBeVisible();
  api.searchModels.mockResolvedValue({
    kind: "error",
    error: AppError.network(new Error("offline")),
  });
  await user.type(screen.getByRole("textbox", { name: "Search models" }), "changed");
  expect(screen.queryByRole("link", { name: "Historical model" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Load more models" })).not.toBeInTheDocument();
  await screen.findByRole("alert");
  expect(api.searchModels).toHaveBeenLastCalledWith(
    expect.objectContaining({ query: "changed", cursor: undefined }),
  );
});
it("stock archive visibility change cannot retain previous rows or reuse their cursor", async () => {
  const user = userEvent.setup();
  api.searchStock.mockResolvedValue({
    kind: "ok",
    data: {
      items: [
        {
          id: "balance",
          assetModelId: "stock-model",
          assetModelName: "Archived tape",
          categoryName: "Default",
          placePath: "Warehouse",
          quantity: 0,
          totalOnHand: 0,
          stockUnitLabel: "roll",
          archived: true,
        },
      ],
      nextCursor: "archive-cursor",
    },
  });
  render(
    <MemoryRouter>
      <StockSearchPage />
    </MemoryRouter>,
  );
  expect(await screen.findByRole("link", { name: "Archived tape" })).toBeVisible();
  api.searchStock.mockResolvedValue({
    kind: "error",
    error: AppError.network(new Error("offline")),
  });
  await user.click(screen.getByRole("switch", { name: "Include archived" }));
  expect(screen.queryByRole("link", { name: "Archived tape" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Load more consumables" })).not.toBeInTheDocument();
  await screen.findByRole("alert");
  expect(api.searchStock).toHaveBeenLastCalledWith(
    expect.objectContaining({ includeArchived: true, cursor: undefined }),
  );
});
