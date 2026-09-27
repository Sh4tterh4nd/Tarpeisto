import { MemoryRouter } from "react-router-dom";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AppError } from "@tarpeisto/api-client";
import { ReviewPage } from "./ReviewPage";
import * as reviewApi from "./reviewApi";
import type { FindingReview } from "./reviewApi";

vi.mock("./reviewApi", () => ({
  listFindings: vi.fn(),
  getFinding: vi.fn(),
  resolveFinding: vi.fn(),
  reviewError: (error: { message: string }) => error.message,
}));

const finding: FindingReview = {
  id: "finding-1",
  auditId: "audit-1",
  assetId: "asset-1",
  type: "MISSING",
  note: "Cable was not found",
  detail: "{}",
  recordedAt: "2026-09-26T12:00:00Z",
  resolved: false,
  resolutionAction: "DISMISS",
  resolvedAt: "",
  applicableActions: ["FOUND_AND_RETURNED", "MARK_LOST", "DISMISS"],
  auditTaskId: "task-1",
  containerAssetId: "container-1",
};

describe("ReviewPage", () => {
  it("requires deliberate loss confirmation and reuses one operation ID on retry", async () => {
    const user = userEvent.setup();
    vi.mocked(reviewApi.listFindings).mockResolvedValue({ kind: "ok", data: [finding] });
    vi.mocked(reviewApi.getFinding).mockResolvedValue({ kind: "ok", data: finding });
    vi.mocked(reviewApi.resolveFinding).mockResolvedValue({
      kind: "error",
      error: AppError.network(new Error("Network unavailable")),
    });

    render(<ReviewPage />, { wrapper: ({ children }) => <MemoryRouter>{children}</MemoryRouter> });
    await screen.findAllByText("Cable was not found");
    await user.click(screen.getByRole("button", { name: /mark lost/i }));
    await user.click(screen.getByRole("button", { name: /save mark lost/i }));
    expect(screen.getByText(/confirm the permanent lifecycle change/i)).toBeInTheDocument();

    await user.click(screen.getByRole("checkbox"));
    await user.click(screen.getByRole("button", { name: /save mark lost/i }));
    await waitFor(() => expect(reviewApi.resolveFinding).toHaveBeenCalledTimes(1));
    await user.click(screen.getByRole("button", { name: /save mark lost/i }));
    await waitFor(() => expect(reviewApi.resolveFinding).toHaveBeenCalledTimes(2));
    const firstOperation = vi.mocked(reviewApi.resolveFinding).mock.calls[0]![1].operationId;
    const retryOperation = vi.mocked(reviewApi.resolveFinding).mock.calls[1]![1].operationId;
    expect(firstOperation).toBe(retryOperation);
  });
});
