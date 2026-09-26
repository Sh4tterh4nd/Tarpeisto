import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { LocationsPage } from "./LocationsPage";

const state = vi.hoisted(() => ({ role: "OWNER" }));
const api = vi.hoisted(() => ({
  listLocations: vi.fn(),
  listLocationStock: vi.fn(),
  createLocation: vi.fn(),
  updateLocation: vi.fn(),
  setLocationArchived: vi.fn(),
}));
vi.mock("../identity/useSession", () => ({ useSession: () => ({ role: state.role }) }));
vi.mock("./inventoryApi", () => ({ ...api, errorMessage: (error: Error) => error.message }));

describe("LocationsPage", () => {
  beforeEach(() => {
    state.role = "OWNER";
    api.listLocations.mockResolvedValue({
      kind: "ok",
      data: [
        {
          id: "shelf",
          name: "Shelf A",
          description: "Top",
          parentLocationId: undefined,
          archived: false,
          version: 0,
          breadcrumbs: ["HQ", "Shelf A"],
          effectivePath: "HQ / Shelf A",
          createdAt: "2026-01-01T00:00:00Z",
          updatedAt: "2026-01-01T00:00:00Z",
        },
      ],
    });
    api.listLocationStock.mockResolvedValue({ kind: "ok", data: [] });
  });
  it("renders location breadcrumbs and management controls for an owner", async () => {
    render(<LocationsPage />);
    expect(await screen.findByText("HQ / Shelf A - Top")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add location" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit" })).toBeInTheDocument();
  });
  it("hides mutation controls from viewers", async () => {
    state.role = "VIEWER";
    render(<LocationsPage />);
    await screen.findByText("HQ / Shelf A - Top");
    expect(screen.queryByRole("button", { name: "Add location" })).not.toBeInTheDocument();
  });

  it("clears an edited form before opening a fresh add dialog", async () => {
    const user = userEvent.setup();
    render(<LocationsPage />);
    await screen.findByText("HQ / Shelf A - Top");
    await user.click(screen.getByRole("button", { name: "Edit" }));
    expect(screen.getByRole("dialog")).toHaveTextContent("Edit location");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "Add location" }));
    expect(screen.getByRole("dialog")).toHaveTextContent("Add location");
    await waitFor(() => expect(screen.getByRole("dialog")).toHaveTextContent("Add location"));
    expect(screen.getAllByRole("textbox")[0]).toHaveValue("");
  });
});
