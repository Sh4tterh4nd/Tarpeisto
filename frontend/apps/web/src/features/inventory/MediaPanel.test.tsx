import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MediaPanel } from "./MediaPanel";

const api = vi.hoisted(() => ({
  getMedia: vi.fn(),
  listMedia: vi.fn(),
  uploadMedia: vi.fn(),
  updateLayoutMedia: vi.fn(),
  deleteMedia: vi.fn(),
}));

const ownReference = {
  id: "asset-photo",
  purpose: "ASSET_REFERENCE",
  contentType: "image/jpeg",
  byteSize: 42,
  caption: "",
  displayOrder: 0,
  imageUrl: "/api/v1/media/asset-photo",
  thumbnailUrl: "/api/v1/media/asset-photo/thumbnail",
  createdAt: "2026-09-26T10:00:00Z",
  version: 1,
};

vi.mock("./mediaApi", () => api);

describe("MediaPanel", () => {
  beforeEach(() => {
    api.getMedia.mockResolvedValue(undefined);
    api.listMedia.mockResolvedValue([
      {
        id: "layout-1",
        purpose: "CONTAINER_LAYOUT",
        contentType: "image/jpeg",
        byteSize: 42,
        caption: "Bottom layer",
        displayOrder: 0,
        imageUrl: "/api/v1/media/layout-1",
        thumbnailUrl: "/api/v1/media/layout-1/thumbnail",
        createdAt: "2026-09-26T10:00:00Z",
        version: 1,
      },
    ]);
  });

  it("shows an ordered container layout and only offers mutations to inventory managers", async () => {
    render(<MediaPanel assetId="asset-1" containerCapable canManage />);

    expect(await screen.findByText("1. Bottom layer")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add layout photo" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove layout photo" })).toBeInTheDocument();
    await waitFor(() =>
      expect(api.listMedia).toHaveBeenCalledWith("/api/v1/assets/asset-1/media/layout"),
    );
  });

  it("keeps the add-photo control and empty state when no reference photo exists", async () => {
    render(<MediaPanel assetModelId="model-1" canManage />);

    expect(await screen.findByText("No reference photo yet.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add photo" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Photo options" })).not.toBeInTheDocument();
  });

  it("offers replace and remove from the menu for an own reference photo", async () => {
    api.getMedia.mockResolvedValue(ownReference);
    api.uploadMedia.mockResolvedValue(undefined);
    api.deleteMedia.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<MediaPanel assetId="asset-1" canManage />);

    await screen.findByAltText("Reference");
    const photoOptions = screen.getByRole("button", { name: "Photo options" });
    expect(photoOptions).toHaveAttribute("aria-haspopup", "menu");
    expect(photoOptions).toHaveAttribute("aria-expanded", "false");
    expect(photoOptions).not.toHaveAttribute("aria-controls");

    await user.click(photoOptions);
    const menu = screen.getByRole("menu");
    expect(photoOptions).toHaveAttribute("aria-expanded", "true");
    expect(document.getElementById(photoOptions.getAttribute("aria-controls") ?? "")).toBeTruthy();
    expect(menu).toHaveAttribute("aria-labelledby", photoOptions.id);
    await user.click(screen.getByRole("menuitem", { name: "Replace photo" }));

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File(["photo"], "photo.jpg")] } });
    await waitFor(() => expect(api.uploadMedia).toHaveBeenCalled());
    expect(input.value).toBe("");

    await user.click(screen.getByRole("button", { name: "Photo options" }));
    await user.click(screen.getByRole("menuitem", { name: "Remove photo" }));
    await waitFor(() => expect(api.deleteMedia).toHaveBeenCalledWith("asset-photo"));
  });

  it("offers replacement but not removal for an inherited model reference photo", async () => {
    api.getMedia.mockResolvedValueOnce(undefined).mockResolvedValueOnce({
      id: "model-photo",
      purpose: "MODEL_REFERENCE",
      contentType: "image/jpeg",
      byteSize: 42,
      caption: "",
      displayOrder: 0,
      imageUrl: "/api/v1/media/model-photo",
      thumbnailUrl: "/api/v1/media/model-photo/thumbnail",
      createdAt: "2026-09-26T10:00:00Z",
      version: 1,
    });
    render(<MediaPanel assetId="asset-1" fallbackAssetModelId="model-1" canManage />);

    expect(await screen.findByText("Using the model reference photo.")).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Photo options" }));
    expect(screen.getByRole("menuitem", { name: "Replace photo" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Remove photo" })).not.toBeInTheDocument();
  });

  it("does not offer photo options to a viewer", async () => {
    api.getMedia.mockResolvedValue(ownReference);
    render(<MediaPanel assetModelId="model-1" canManage={false} />);

    expect(await screen.findByAltText("Reference")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Photo options" })).not.toBeInTheDocument();
  });
});
