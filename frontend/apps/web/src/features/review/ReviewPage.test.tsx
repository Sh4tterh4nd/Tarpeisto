import { MemoryRouter } from "react-router-dom";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppError } from "@tarpeisto/api-client";
import { ReviewPage } from "./ReviewPage";
import * as reviewApi from "./reviewApi";
import type { FindingReview } from "./reviewApi";

vi.mock("./reviewApi", () => ({
  reconcilePackingUntilComplete: vi.fn().mockResolvedValue({ kind: "ok", data: undefined }),
  listFindings: vi.fn(),
  getFinding: vi.fn(),
  resolveFinding: vi.fn(),
  reviewError: (error: { message: string }) => error.message,
}));

const session = vi.hoisted(() => ({
  principal: { userId: "owner", organizationId: "org", role: "OWNER" },
}));
vi.mock("../identity/useSession", () => ({ useSession: () => session }));

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
  beforeEach(() => {
    vi.clearAllMocks();
    session.principal = { userId: "owner", organizationId: "org", role: "OWNER" };
    vi.mocked(reviewApi.reconcilePackingUntilComplete).mockResolvedValue({
      kind: "ok",
      data: undefined,
    });
  });
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
  it("clears the confirmation warning after a successful permanent decision and shows the empty queue", async () => {
    const user = userEvent.setup();
    vi.mocked(reviewApi.listFindings).mockResolvedValue({ kind: "ok", data: [finding] });
    vi.mocked(reviewApi.getFinding).mockResolvedValue({ kind: "ok", data: finding });
    vi.mocked(reviewApi.resolveFinding).mockResolvedValue({
      kind: "ok",
      data: { ...finding, resolved: true, resolutionAction: "MARK_LOST" },
    });
    render(
      <MemoryRouter>
        <ReviewPage />
      </MemoryRouter>,
    );
    await screen.findAllByText("Cable was not found");
    await user.click(screen.getByRole("button", { name: /mark lost/i }));
    await user.click(screen.getByRole("button", { name: /save mark lost/i }));
    expect(screen.getByText(/confirm the permanent lifecycle change/i)).toBeInTheDocument();
    expect(reviewApi.resolveFinding).not.toHaveBeenCalled();
    await user.click(screen.getByRole("checkbox"));
    await user.click(screen.getByRole("button", { name: /save mark lost/i }));
    expect(await screen.findByText("No unresolved findings.")).toBeInTheDocument();
    expect(screen.queryByText(/confirm the permanent lifecycle change/i)).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "0 findings need review" })).toBeInTheDocument();
    expect(reviewApi.resolveFinding).toHaveBeenCalledTimes(1);
  });
  it("waits for reconciliation before review GET and offers visible retry after failure", async () => {
    vi.mocked(reviewApi.reconcilePackingUntilComplete).mockResolvedValueOnce({
      kind: "error",
      error: AppError.network(new Error("Offline")),
    });
    vi.mocked(reviewApi.listFindings).mockResolvedValue({ kind: "ok", data: [finding] });
    vi.mocked(reviewApi.getFinding).mockResolvedValue({ kind: "ok", data: finding });
    render(
      <MemoryRouter>
        <ReviewPage />
      </MemoryRouter>,
    );
    await screen.findByText(/Packing review refresh failed/);
    expect(screen.getByRole("heading", { name: "Review findings" })).toBeInTheDocument();
    expect(reviewApi.listFindings).not.toHaveBeenCalled();
    expect(screen.queryByText("No unresolved findings.")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Refresh review" }));
    await screen.findAllByText("Cable was not found");
    expect(reviewApi.reconcilePackingUntilComplete).toHaveBeenCalledTimes(2);
  });

  it("cancels the previous tenant sweep before any finding GET and never sweeps for non-managers", async () => {
    let finish: (
      value: Awaited<ReturnType<typeof reviewApi.reconcilePackingUntilComplete>>,
    ) => void = () => {};
    let isCurrent: () => boolean = () => true;
    vi.mocked(reviewApi.reconcilePackingUntilComplete).mockImplementationOnce((current) => {
      isCurrent = current;
      return new Promise((resolve) => {
        finish = resolve;
      });
    });
    vi.mocked(reviewApi.listFindings).mockResolvedValue({ kind: "ok", data: [] });
    const view = render(
      <MemoryRouter>
        <ReviewPage />
      </MemoryRouter>,
    );
    await waitFor(() => expect(reviewApi.reconcilePackingUntilComplete).toHaveBeenCalledTimes(1));
    session.principal = { userId: "viewer", organizationId: "other", role: "VIEWER" };
    view.rerender(
      <MemoryRouter>
        <ReviewPage />
      </MemoryRouter>,
    );
    await screen.findByText("No unresolved findings.");
    expect(isCurrent()).toBe(false);
    await act(async () => finish({ kind: "ok", data: undefined }));
    expect(reviewApi.listFindings).toHaveBeenCalledTimes(1);
    expect(reviewApi.getFinding).not.toHaveBeenCalled();
    expect(reviewApi.reconcilePackingUntilComplete).toHaveBeenCalledTimes(1);
  });
  it("clears a previous finding draft when reconciliation removes it during refresh", async () => {
    const next = { ...finding, id: "finding-2", note: "Different missing unit" };
    vi.mocked(reviewApi.listFindings)
      .mockResolvedValueOnce({ kind: "ok", data: [finding] })
      .mockResolvedValueOnce({ kind: "ok", data: [next] });
    vi.mocked(reviewApi.getFinding).mockImplementation(async (id) => ({
      kind: "ok",
      data: id === next.id ? next : finding,
    }));
    render(
      <MemoryRouter>
        <ReviewPage />
      </MemoryRouter>,
    );
    await screen.findAllByText("Cable was not found");
    await userEvent.type(
      screen.getByRole("textbox", { name: "Decision note" }),
      "Reason belonging to finding A",
    );
    await userEvent.click(screen.getByRole("button", { name: "Refresh review" }));
    await screen.findAllByText("Different missing unit");
    expect(screen.getByRole("textbox", { name: "Decision note" })).toHaveValue("");
    await userEvent.click(screen.getByRole("button", { name: "Dismiss" }));
    await userEvent.click(screen.getByRole("button", { name: "Save dismiss" }));
    expect(screen.getByText("Dismissal requires a reason.")).toBeInTheDocument();
    expect(reviewApi.resolveFinding).not.toHaveBeenCalled();
    expect(reviewApi.getFinding).toHaveBeenLastCalledWith("finding-2");
  });
});
