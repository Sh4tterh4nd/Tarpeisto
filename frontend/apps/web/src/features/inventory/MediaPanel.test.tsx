import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MediaPanel } from "./MediaPanel";

const api = vi.hoisted(() => ({
  getMedia: vi.fn(),
  listMedia: vi.fn(),
  uploadMedia: vi.fn(),
  updateLayoutMedia: vi.fn(),
  deleteMedia: vi.fn(),
}));

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

  it("keeps photo controls read-only for a viewer", async () => {
    render(<MediaPanel assetModelId="model-1" canManage={false} />);

    expect(await screen.findByText("No reference photo yet.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add photo" })).not.toBeInTheDocument();
  });

  it("does not offer removal for an inherited model reference photo", async () => {
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
    expect(screen.queryByRole("button", { name: "Remove photo" })).not.toBeInTheDocument();
  });
});
