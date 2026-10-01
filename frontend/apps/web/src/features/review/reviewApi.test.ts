import { afterEach, describe, expect, it, vi } from "vitest";
import { apiClient } from "@tarpeisto/api-client";
import { reconcilePackingUntilComplete } from "./reviewApi";

describe("bounded packing reconciliation client", () => {
  afterEach(() => vi.restoreAllMocks());

  it("follows every bounded cursor before reporting completion", async () => {
    const post = vi
      .spyOn(apiClient, "POST")
      .mockResolvedValueOnce({
        data: { inspectedCount: 100, dismissedCount: 3, nextCursor: "page-two" },
        response: new Response("", { status: 200 }),
      })
      .mockResolvedValueOnce({
        data: { inspectedCount: 5, dismissedCount: 0 },
        response: new Response("", { status: 200 }),
      });
    expect(await reconcilePackingUntilComplete(() => true)).toEqual({
      kind: "ok",
      data: undefined,
    });
    expect(post).toHaveBeenNthCalledWith(1, "/api/v1/findings/reconcile-packing", {
      params: { query: { cursor: undefined } },
    });
    expect(post).toHaveBeenNthCalledWith(2, "/api/v1/findings/reconcile-packing", {
      params: { query: { cursor: "page-two" } },
    });
  });

  it("stops between pages after identity cancellation and returns errors without a read fallback", async () => {
    let active = true;
    const post = vi.spyOn(apiClient, "POST").mockImplementationOnce(async () => {
      active = false;
      return {
        data: { inspectedCount: 100, dismissedCount: 0, nextCursor: "old" },
        response: new Response("", { status: 200 }),
      };
    });
    expect(await reconcilePackingUntilComplete(() => active)).toEqual({ kind: "cancelled" });
    expect(post).toHaveBeenCalledTimes(1);
    active = true;
    post.mockRejectedValueOnce(new Error("Disconnected"));
    expect((await reconcilePackingUntilComplete(() => active)).kind).toBe("error");
  });
});
