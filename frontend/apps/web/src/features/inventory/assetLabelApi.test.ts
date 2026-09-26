import { afterEach, describe, expect, it, vi } from "vitest";
import { createAssetLabelPdf, downloadAssetLabelFile } from "./assetLabelApi";

describe("downloadAssetLabelFile", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("uses the generated PDF operation and receives its response as a blob", async () => {
    const fetch = vi.fn((input: RequestInfo | URL) => {
      void input;
      return Promise.resolve(
        new Response(new Blob(["label"], { type: "application/pdf" }), {
          status: 200,
          headers: { "content-type": "application/pdf" },
        }),
      );
    });
    vi.stubGlobal("fetch", fetch);

    const result = await createAssetLabelPdf({
      assetIds: ["asset-1"],
      format: "A4_70X36_24",
      skipFirstPositions: 3,
    });

    expect(result).toMatchObject({ kind: "ok" });
    if (result.kind === "ok") expect(result.data).toBeInstanceOf(Blob);
    const request = fetch.mock.calls[0]?.[0] as Request;
    expect(request.url).toContain("/api/v1/asset-labels/pdf");
    await expect(request.json()).resolves.toEqual({
      assetIds: ["asset-1"],
      format: "A4_70X36_24",
      skipFirstPositions: 3,
    });
  });

  it("uses an object URL, the requested filename, and cleans the URL up after clicking", () => {
    const createObjectUrl = vi.fn(() => "blob:asset-label");
    const revokeObjectUrl = vi.fn();
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: createObjectUrl });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: revokeObjectUrl });
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    const blob = new Blob(["label"], { type: "application/pdf" });

    downloadAssetLabelFile(blob, "asset-labels-70x36.pdf");

    expect(createObjectUrl).toHaveBeenCalledWith(blob);
    expect(click).toHaveBeenCalledOnce();
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:asset-label");
    expect(document.querySelector('a[download="asset-labels-70x36.pdf"]')).toBeNull();
  });
});
