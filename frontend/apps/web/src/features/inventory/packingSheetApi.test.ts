import { afterEach, describe, expect, it, vi } from "vitest";
import { apiClient } from "@bigcontainers/api-client";
import { downloadPackingSheet } from "./packingSheetApi";

describe("downloadPackingSheet", () => {
  afterEach(() => vi.restoreAllMocks());

  it("uses the generated PDF endpoint and preserves its blob", async () => {
    const blob = new Blob(["pdf"], { type: "application/pdf" });
    vi.spyOn(apiClient, "GET").mockResolvedValue({
      data: blob,
      response: new Response(blob),
    } as never);

    await expect(downloadPackingSheet("container-1")).resolves.toEqual({ kind: "ok", data: blob });
    expect(apiClient.GET).toHaveBeenCalledWith("/api/v1/assets/{assetId}/packing-sheet.pdf", {
      params: { path: { assetId: "container-1" } },
      parseAs: "blob",
    });
  });

  it("preserves server problem details for download errors", async () => {
    const problem = {
      type: "about:blank",
      title: "Asset not found",
      status: 404,
      errorCode: "NOT_FOUND",
    };
    vi.spyOn(apiClient, "GET").mockResolvedValue({
      error: problem,
      response: new Response(null, { status: 404 }),
    } as never);
    const result = await downloadPackingSheet("missing");
    expect(result.kind).toBe("error");
    if (result.kind === "error") {
      expect(result.error.status).toBe(404);
      expect(result.error.message).toBe("Asset not found");
      expect(result.error.errorCode).toBe("NOT_FOUND");
    }
  });

  it("turns transport failures into retryable network errors", async () => {
    vi.spyOn(apiClient, "GET").mockRejectedValue(new Error("Connection interrupted"));
    const result = await downloadPackingSheet("container-1");
    expect(result.kind).toBe("error");
    if (result.kind === "error") {
      expect(result.error.kind).toBe("network");
      expect(result.error.message).toBe("Connection interrupted");
    }
  });
});
