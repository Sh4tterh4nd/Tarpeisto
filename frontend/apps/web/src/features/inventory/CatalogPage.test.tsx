import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { CatalogPage } from "./CatalogPage";

const testState = vi.hoisted(() => ({ role: "OWNER" }));
const api = vi.hoisted(() => ({
  listCategories: vi.fn(),
  listAssetModels: vi.fn(),
  createCategory: vi.fn(),
  updateCategory: vi.fn(),
  setCategoryArchived: vi.fn(),
  createAssetModel: vi.fn(),
  setAssetModelArchived: vi.fn(),
}));

vi.mock("../identity/useSession", () => ({
  useSession: () => ({ role: testState.role }),
}));

vi.mock("./inventoryApi", () => ({
  ...api,
  errorMessage: (error: Error) => error.message,
}));

describe("CatalogPage", () => {
  beforeEach(() => {
    testState.role = "OWNER";
    api.listCategories.mockResolvedValue({
      kind: "ok",
      data: [
        {
          id: "category-1",
          name: "Networking",
          color: "#245F8A",
          archived: false,
          createdAt: "2026-09-26T10:00:00Z",
          updatedAt: "2026-09-26T10:00:00Z",
        },
      ],
    });
    api.listAssetModels.mockResolvedValue({
      kind: "ok",
      data: [
        {
          id: "model-1",
          name: "UniFi AP-HD",
          description: "Access point",
          categoryId: "category-1",
          replacementUrl: "",
          trackingMode: "SERIALIZED_ASSET",
          stockUnitLabel: "",
          lowStockThreshold: 0,
          canContainAssets: false,
          archived: false,
          createdAt: "2026-09-26T10:00:00Z",
          updatedAt: "2026-09-26T10:00:00Z",
        },
        {
          id: "model-2",
          name: "Gaffer tape 50 mm",
          description: "",
          categoryId: "category-1",
          replacementUrl: "",
          trackingMode: "QUANTITY_STOCK",
          stockUnitLabel: "roll",
          lowStockThreshold: 5,
          canContainAssets: false,
          archived: false,
          createdAt: "2026-09-26T10:00:00Z",
          updatedAt: "2026-09-26T10:00:00Z",
        },
      ],
    });
  });

  function renderPage() {
    render(
      <MemoryRouter>
        <CatalogPage />
      </MemoryRouter>,
    );
  }

  it("presents serialized and quantity-tracked models with their category", async () => {
    renderPage();

    expect(await screen.findByRole("link", { name: "UniFi AP-HD" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Gaffer tape 50 mm" })).toBeInTheDocument();
    expect(screen.getByText("Serialized asset")).toBeInTheDocument();
    expect(screen.getByText("Quantity · roll")).toBeInTheDocument();
    expect(screen.getAllByText("Networking").length).toBeGreaterThan(0);
  });

  it("keeps mutation controls out of a Viewer catalog", async () => {
    testState.role = "VIEWER";
    renderPage();

    expect(await screen.findByRole("heading", { name: "Inventory catalog" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Asset model" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Category" })).not.toBeInTheDocument();
  });

  it("opens the category editor with an accessible color preview", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Category" }));

    expect(screen.getByRole("dialog", { name: "New category" })).toBeInTheDocument();
    expect(screen.getByLabelText("Category color")).toHaveValue("#2f6b5c");
  });
});
