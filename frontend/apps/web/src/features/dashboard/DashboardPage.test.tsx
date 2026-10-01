import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppError } from "@tarpeisto/api-client";
import { reconcilePackingUntilComplete } from "../review/reviewApi";
import { DashboardPage } from "./DashboardPage";
import { getDashboard, getDashboardPage, type DashboardPage as QueuePage } from "./dashboardApi";

vi.mock("../review/reviewApi", () => ({
  reconcilePackingUntilComplete: vi.fn().mockResolvedValue({ kind: "ok", data: undefined }),
  reviewError: (error: { message: string }) => error.message,
}));
vi.mock("./dashboardApi", () => ({ getDashboard: vi.fn(), getDashboardPage: vi.fn() }));
const session = vi.hoisted(() => ({
  principal: { userId: "owner", organizationId: "org", role: "OWNER" },
}));
vi.mock("../identity/useSession", () => ({ useSession: () => session }));
const initial: QueuePage = {
  queue: "AUDITS",
  count: 2,
  nextCursor: "next",
  items: [
    {
      id: "one",
      label: "Cable case",
      code: "ABC123",
      state: "BLOCKED",
      reason: "Complete child audit first",
      actionLabel: "Open audit",
      actionPath: "/audits/tasks/one",
      readOnly: false,
    },
  ],
};

describe("operational workboard", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    session.principal = { userId: "owner", organizationId: "org", role: "OWNER" };
    vi.mocked(reconcilePackingUntilComplete).mockResolvedValue({ kind: "ok", data: undefined });
  });
  it("retains dependency context and appends the next queue page without losing earlier tasks", async () => {
    vi.mocked(getDashboard).mockResolvedValue({ kind: "ok", data: { queues: [initial] } });
    vi.mocked(getDashboardPage).mockResolvedValue({
      kind: "ok",
      data: {
        ...initial,
        nextCursor: undefined,
        items: [
          {
            ...initial.items[0]!,
            id: "two",
            label: "Lighting case",
            actionPath: "/audits/tasks/two",
          },
        ],
      },
    });
    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    const queue = await screen.findByRole("region", { name: "Audits to complete" });
    expect(within(queue).getByText("Complete child audit first")).toBeInTheDocument();
    expect(within(queue).getByRole("link", { name: "Open audit" })).toHaveAttribute(
      "href",
      "/audits/tasks/one",
    );
    await userEvent.click(within(queue).getByRole("button", { name: /Show more/ }));
    expect(await within(queue).findByText("Lighting case (ABC123)")).toBeInTheDocument();
    expect(within(queue).getByText("Cable case (ABC123)")).toBeInTheDocument();
    expect(getDashboardPage).toHaveBeenCalledWith("AUDITS", "next");
    expect(within(queue).queryByRole("button", { name: /Show more/ })).not.toBeInTheDocument();
  });
  it("makes read-only review links clear and preserves server-authorized destinations", async () => {
    vi.mocked(getDashboard).mockResolvedValue({
      kind: "ok",
      data: {
        queues: [
          {
            ...initial,
            queue: "REVIEW",
            count: 1,
            nextCursor: undefined,
            items: [
              {
                ...initial.items[0]!,
                readOnly: true,
                actionLabel: "View container",
                actionPath: "/inventory/assets/container",
              },
            ],
          },
        ],
      },
    });
    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    expect(await screen.findByText("Read only")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "View container" })).toHaveAttribute(
      "href",
      "/inventory/assets/container",
    );
  });
  it("hides previous tenant queues immediately and ignores a deferred old-role response", async () => {
    let resolveOld: (value: Awaited<ReturnType<typeof getDashboard>>) => void = () => {};
    vi.mocked(getDashboard)
      .mockResolvedValueOnce({ kind: "ok", data: { queues: [initial] } })
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveOld = resolve;
          }),
      )
      .mockResolvedValueOnce({
        kind: "ok",
        data: {
          queues: [{ ...initial, items: [{ ...initial.items[0]!, label: "New tenant case" }] }],
        },
      });
    const view = render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    await screen.findByText("Cable case (ABC123)");
    session.principal = { userId: "owner", organizationId: "org", role: "VIEWER" };
    view.rerender(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    expect(screen.queryByText("Cable case (ABC123)")).not.toBeInTheDocument();
    await vi.waitFor(() => expect(getDashboard).toHaveBeenCalledTimes(2));
    session.principal = { userId: "other", organizationId: "other-org", role: "VIEWER" };
    view.rerender(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    await screen.findByText("New tenant case (ABC123)");
    await act(async () => resolveOld({ kind: "ok", data: { queues: [initial] } }));
    expect(screen.queryByText("Cable case (ABC123)")).not.toBeInTheDocument();
    session.principal = { userId: "owner", organizationId: "org", role: "OWNER" };
  });
  it("waits for the full packing sweep before fetching queues and exposes failure retry", async () => {
    vi.mocked(reconcilePackingUntilComplete).mockResolvedValueOnce({
      kind: "error",
      error: AppError.network(new Error("Offline")),
    });
    vi.mocked(getDashboard).mockResolvedValue({ kind: "ok", data: { queues: [initial] } });
    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    expect(await screen.findByText(/Packing review refresh failed/)).toBeInTheDocument();
    expect(getDashboard).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "Refresh workboard" }));
    await screen.findByText("Cable case (ABC123)");
    expect(reconcilePackingUntilComplete).toHaveBeenCalledTimes(2);
    expect(vi.mocked(reconcilePackingUntilComplete).mock.invocationCallOrder[1]).toBeLessThan(
      vi.mocked(getDashboard).mock.invocationCallOrder[0]!,
    );
  });

  it("never sweeps for a Viewer and cancels a pending old-manager sweep on identity change", async () => {
    let finish: (
      value: Awaited<ReturnType<typeof reconcilePackingUntilComplete>>,
    ) => void = () => {};
    let isCurrent: () => boolean = () => true;
    vi.mocked(reconcilePackingUntilComplete).mockImplementationOnce((current) => {
      isCurrent = current;
      return new Promise((resolve) => {
        finish = resolve;
      });
    });
    vi.mocked(getDashboard).mockResolvedValue({ kind: "ok", data: { queues: [initial] } });
    const view = render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    await vi.waitFor(() => expect(reconcilePackingUntilComplete).toHaveBeenCalledTimes(1));
    session.principal = { userId: "viewer", organizationId: "new-org", role: "VIEWER" };
    view.rerender(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    await screen.findByText("Cable case (ABC123)");
    expect(isCurrent()).toBe(false);
    await act(async () => finish({ kind: "ok", data: undefined }));
    expect(getDashboard).toHaveBeenCalledTimes(1);
    expect(reconcilePackingUntilComplete).toHaveBeenCalledTimes(1);
  });
});
