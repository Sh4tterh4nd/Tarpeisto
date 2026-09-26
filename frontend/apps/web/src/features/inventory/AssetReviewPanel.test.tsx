import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { apiClient } from "@bigcontainers/api-client";
import { AssetReviewPanel } from "./AssetReviewPanel";

vi.mock("@bigcontainers/api-client", () => ({
  apiClient: { GET: vi.fn(), POST: vi.fn(), PUT: vi.fn() },
}));

describe("AssetReviewPanel", () => {
  it("shows persisted seal and verification state", async () => {
    vi.mocked(apiClient.GET).mockResolvedValue({ data: [], response: new Response() } as never);
    render(
      <AssetReviewPanel
        assetId="asset-1"
        canManage
        containerCapable
        lifecycleState="ACTIVE"
        sealable
        sealState="VERIFIED"
        sealVerifiedAt="2026-09-26T12:00:00Z"
        lastVerifiedAt="2026-09-26T11:00:00Z"
        replacesAssetId="older-asset"
      />,
    );
    expect(await screen.findByText(/seal state: verified/i)).toBeInTheDocument();
    expect(screen.getByText(/last verified:/i)).toBeInTheDocument();
    expect(screen.getByText(/replaces asset older-asset/i)).toBeInTheDocument();
  });

  it("requires repair text before opening and explains a new replacement identity", async () => {
    const user = userEvent.setup();
    vi.mocked(apiClient.GET).mockResolvedValue({ data: [], response: new Response() } as never);
    render(
      <AssetReviewPanel
        assetId="asset-1"
        canManage
        containerCapable={false}
        lifecycleState="LOST"
        sealable={false}
        sealState="UNSEALED"
      />,
    );
    await user.click(screen.getByRole("button", { name: "Open repair" }));
    expect(screen.getByText(/enter a repair reference/i)).toBeInTheDocument();
    expect(screen.getByText(/new identity and code/i)).toBeInTheDocument();
  });
});
