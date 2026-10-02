import { StrictMode } from "react";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuditTaskPage } from "./AuditTaskPage";
import type { useAuditQueue } from "./useAuditQueue";
import type { ContainerAudit } from "./auditApi";
import type { AuditAcknowledgement } from "../../data/outbox/auditOutbox";

const mocks = vi.hoisted(() => ({
  queue: vi.fn(),
  complete: vi.fn(),
  candidates: vi.fn(),
  session: { role: "OPERATOR_AUDITOR", principal: { organizationId: "org", userId: "actor" } },
  listeners: new Set<(value: AuditAcknowledgement) => void>(),
}));
vi.mock("./useAuditQueue", () => ({ useAuditQueue: mocks.queue }));
vi.mock("./auditApi", () => ({
  completeAudit: mocks.complete,
  startAudit: vi.fn(),
  getAuditTask: vi.fn(),
  getAuditManualCandidates: mocks.candidates,
}));
vi.mock("../identity/useSession", () => ({ useSession: () => mocks.session }));
vi.mock("./AuditEvidencePanel", () => ({ AuditEvidencePanel: () => null }));
vi.mock("../temporary-access/TemporaryInvitationsPanel", () => ({
  TemporaryInvitationsPanel: () => null,
}));
vi.mock("../scanner/ScannerViewport", () => ({
  ScannerViewport: ({
    paused,
    onCode,
    onManual,
  }: {
    paused: boolean;
    onCode: (code: string) => void;
    onManual: () => void;
  }) => (
    <div data-testid="camera" data-paused={String(paused)}>
      <button onClick={() => onCode("ABC123")}>Camera item</button>
      <button onClick={() => onCode("000000")}>Camera container</button>
      <button onClick={() => onCode("WRONG0")}>Camera wrong</button>
      <button onClick={onManual}>Manual code</button>
    </div>
  ),
}));
const original: ContainerAudit = {
  id: "audit",
  taskId: "task",
  batchId: "batch",
  containerAssetId: "case",
  state: "IN_PROGRESS",
  blockingReasons: [],
  scans: [],
  findings: [],
  expectedRequirements: [
    {
      id: "model",
      displayOrder: 0,
      type: "MODEL_QUANTITY",
      assetModelId: "cable",
      requiredQuantity: 2,
      matchedQuantity: 0,
      snapshot: JSON.stringify({ modelName: "Cable" }),
      satisfied: false,
    },
  ],
};
const scan = (operationId: string) => ({
  id: "scan",
  operationId,
  assetId: "unit",
  assetModelId: "cable",
  assetCode: "ABC123",
  outcome: "EXPECTED_MODEL",
  scannedAt: "2026-10-02T10:00:00Z",
  undone: false,
  contextSnapshot: JSON.stringify({ assetName: "Cable 1" }),
  recordedByDisplayName: "Named helper",
});
let queue: ReturnType<typeof useAuditQueue>;
const onAcknowledged = (listener: (value: AuditAcknowledgement) => void) => {
  mocks.listeners.add(listener);
  return () => {
    mocks.listeners.delete(listener);
  };
};
function view() {
  return (
    <StrictMode>
      <MemoryRouter initialEntries={["/audits/tasks/task"]}>
        <Routes>
          <Route path="/audits/tasks/:taskId" element={<AuditTaskPage />} />
        </Routes>
      </MemoryRouter>
    </StrictMode>
  );
}
function ack(operationId: string, audit: ContainerAudit, local = true) {
  act(() => {
    for (const listener of mocks.listeners)
      listener({ operationId, partition: "org:actor", taskId: "task", audit, local });
  });
}
function mount() {
  return render(view());
}
beforeEach(() => {
  mocks.listeners.clear();
  mocks.session.principal = { organizationId: "org", userId: "actor" };
  mocks.session.role = "OPERATOR_AUDITOR";
  queue = {
    audit: structuredClone(original),
    container: { id: "case", displayName: "Audit case", publicCode: "000000", sealable: false },
    commands: [],
    error: undefined,
    setError: vi.fn(),
    enqueue: vi.fn(async (command) =>
      command.kind === "photo" ? undefined : command.body.operationId,
    ),
    findingWithPhotos: vi.fn(async (command) => command.body.operationId),
    accept: vi.fn(),
    retry: vi.fn(),
    cancel: vi.fn(),
    onAcknowledged,
    settledOperation: vi.fn(async () => undefined),
    complete: vi.fn(async (action) => action("org:actor", new AbortController().signal)),
    syncStatus: "Online",
    completionBlocked: false,
  } as typeof queue;
  mocks.queue.mockImplementation(() => queue);
  mocks.complete.mockResolvedValue({ kind: "ok", data: { ...original, state: "COMPLETED" } });
  mocks.candidates.mockResolvedValue({
    kind: "ok",
    data: {
      items: [
        {
          id: "unit",
          displayName: "Cable 1",
          publicCode: "ABC123",
          assetModelId: "cable",
          modelName: "Cable",
          active: true,
        },
      ],
    },
  });
});
describe("camera audit workflow", () => {
  it("keeps forms in dialogs, orders missing requirements first and loads only associated units", async () => {
    const user = userEvent.setup();
    mount();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Finish" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Details" }));
    expect(screen.getByTestId("camera")).toHaveAttribute("data-paused", "true");
    expect(await screen.findByText("0/2 found")).toBeInTheDocument();
    expect(mocks.candidates).toHaveBeenCalledWith("audit", "", undefined);
    await user.click(screen.getByText("Cable", { selector: "p" }));
    expect(screen.getAllByText(/Cable 1 - ABC123 \/ Not yet scanned/).length).toBeGreaterThan(0);
  });
  it("flashes only a fresh current local expected acknowledgement, resets after snapshots and never flashes history or foreign operations", async () => {
    const user = userEvent.setup();
    queue.audit = { ...original, scans: [{ ...scan("history"), id: "historic" }] };
    const rendered = mount();
    expect(screen.queryByTestId("expected-scan-flash")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Camera item" }));
    const command = vi.mocked(queue.enqueue).mock.calls[0]![0];
    const operationId = command.kind === "scan" ? command.body.operationId : "";
    ack(operationId, { ...original, scans: [scan(operationId)] });
    expect(screen.getByTestId("expected-scan-flash")).toBeInTheDocument();
    queue = { ...queue, audit: { ...original, scans: [scan(operationId)] } };
    rendered.rerender(view());
    await waitFor(() =>
      expect(screen.queryByTestId("expected-scan-flash")).not.toBeInTheDocument(),
    );
    await user.click(screen.getByRole("button", { name: "Camera item" }));
    const next = vi.mocked(queue.enqueue).mock.calls[1]![0];
    ack(
      next.kind === "scan" ? next.body.operationId : "",
      { ...original, scans: [scan(next.kind === "scan" ? next.body.operationId : "")] },
      false,
    );
    expect(screen.getByTestId("expected-scan-flash")).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByTestId("expected-scan-flash")).not.toBeInTheDocument(),
    );
    ack("foreign-op", { ...original, scans: [scan("foreign-op")] }, false);
    expect(screen.queryByTestId("expected-scan-flash")).not.toBeInTheDocument();
  });
  it("finishes fast duplicates and unknown feedback without green or waiting for a pending render", async () => {
    const user = userEvent.setup();
    mount();
    await user.click(screen.getByRole("button", { name: "Camera item" }));
    let op = vi.mocked(queue.enqueue).mock.calls[0]![0];
    ack(op.kind === "scan" ? op.body.operationId : "", original);
    expect(screen.getByText(/Duplicate \/ no new scan recorded/)).toBeInTheDocument();
    expect(screen.queryByTestId("expected-scan-flash")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Camera item" }));
    op = vi.mocked(queue.enqueue).mock.calls[1]![0];
    ack(op.kind === "scan" ? op.body.operationId : "", {
      ...original,
      findings: [
        {
          id: "unknown",
          sourceOperationId: op.kind === "scan" ? op.body.operationId : "",
          type: "UNKNOWN_CODE",
          detail: "{}",
        },
      ],
    });
    expect(screen.getByText(/Unknown code recorded for review/)).toBeInTheDocument();
  });
  it("does not replace a fast acknowledgement with pending feedback when enqueue resolves later", async () => {
    vi.mocked(queue.enqueue).mockImplementation(async (command) => {
      if (command.kind !== "scan") return undefined;
      const op = command.body.operationId;
      for (const listener of mocks.listeners)
        listener({
          operationId: op,
          partition: "org:actor",
          taskId: "task",
          audit: { ...original, scans: [scan(op)] },
          local: false,
        });
      return op;
    });
    const user = userEvent.setup();
    mount();
    await user.click(screen.getByRole("button", { name: "Camera item" }));
    expect(screen.getByText("Cable 1 - ABC123 - EXPECTED MODEL")).toBeInTheDocument();
    expect(screen.queryByText(/Saved, pending confirmation/)).not.toBeInTheDocument();
    expect(screen.getByTestId("expected-scan-flash")).toBeInTheDocument();
  });
  it("keeps the newest unit and report target when older peer acknowledgements arrive late", async () => {
    const user = userEvent.setup();
    mount();
    await user.click(screen.getByRole("button", { name: "Camera item" }));
    const a = vi.mocked(queue.enqueue).mock.calls[0]![0];
    await user.click(screen.getByRole("button", { name: "Manual code" }));
    await user.clear(screen.getByRole("textbox", { name: "Scan or enter item code" }));
    await user.type(
      screen.getByRole("textbox", { name: "Scan or enter item code" }),
      "ABC124{Enter}",
    );
    const b = vi.mocked(queue.enqueue).mock.calls[1]![0];
    const bOp = b.kind === "scan" ? b.body.operationId : "",
      aOp = a.kind === "scan" ? a.body.operationId : "";
    ack(
      bOp,
      {
        ...original,
        scans: [
          {
            ...scan(bOp),
            id: "b",
            assetId: "unit-b",
            assetCode: "ABC124",
            contextSnapshot: JSON.stringify({ assetName: "Cable B" }),
          },
        ],
      },
      false,
    );
    ack(aOp, { ...original, scans: [scan(aOp)] }, false);
    expect(screen.getByText("Cable B - ABC124 - EXPECTED MODEL")).toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "Report" }));
    await user.click(screen.getByRole("button", { name: "Save report" }));
    expect(queue.findingWithPhotos).toHaveBeenCalledWith(
      expect.objectContaining({ body: expect.objectContaining({ assetId: "unit-b" }) }),
      [],
    );
  });
  it("reports an unknown latest code without targeting the previous known scan", async () => {
    queue.audit = { ...original, scans: [scan("old-known")] };
    const user = userEvent.setup();
    mount();
    await user.click(screen.getByRole("button", { name: "Camera item" }));
    const command = vi.mocked(queue.enqueue).mock.calls[0]![0],
      op = command.kind === "scan" ? command.body.operationId : "";
    ack(op, {
      ...queue.audit!,
      findings: [{ id: "unknown", type: "UNKNOWN_CODE", sourceOperationId: op, detail: "{}" }],
    });
    await user.click(screen.getByRole("button", { name: "Report" }));
    await user.click(screen.getByRole("button", { name: "Save report" }));
    expect(queue.findingWithPhotos).toHaveBeenCalledWith(
      expect.objectContaining({
        body: expect.objectContaining({ type: "UNKNOWN_CODE", assetId: undefined }),
      }),
      [],
    );
  });
  it("does not let an earlier enqueue return overwrite a newer acknowledged unit", async () => {
    let firstReturn: (op: string) => void = () => {};
    let firstOp = "";
    let count = 0;
    vi.mocked(queue.enqueue).mockImplementation(async (command) => {
      if (command.kind !== "scan") return undefined;
      const op = command.body.operationId;
      if (++count === 1) {
        firstOp = op;
        return new Promise<string>((resolve) => {
          firstReturn = resolve;
        });
      }
      for (const listener of mocks.listeners)
        listener({
          operationId: op,
          partition: "org:actor",
          taskId: "task",
          audit: {
            ...original,
            scans: [
              {
                ...scan(op),
                assetCode: "ABC124",
                contextSnapshot: JSON.stringify({ assetName: "Cable B" }),
              },
            ],
          },
          local: true,
        });
      return op;
    });
    const user = userEvent.setup();
    mount();
    await user.click(screen.getByRole("button", { name: "Camera item" }));
    await user.click(screen.getByRole("button", { name: "Manual code" }));
    await user.clear(screen.getByRole("textbox", { name: "Scan or enter item code" }));
    await user.type(
      screen.getByRole("textbox", { name: "Scan or enter item code" }),
      "ABC124{Enter}",
    );
    await act(async () => firstReturn(firstOp));
    expect(screen.getByText("Cable B - ABC124 - EXPECTED MODEL")).toBeInTheDocument();
    expect(screen.queryByText(/Saved, pending confirmation/)).not.toBeInTheDocument();
  });
  it("resumes live camera for a fresh closing rescan, rejects a wrong container and prompts missing contents", async () => {
    const user = userEvent.setup();
    mount();
    await user.click(screen.getByRole("button", { name: "Finish" }));
    expect(screen.getByText("Cable - 2 remaining")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Scan closed container" })).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: /remaining expected/ }));
    await user.click(screen.getByRole("button", { name: "Scan closed container" }));
    await waitFor(() =>
      expect(screen.getByTestId("camera")).toHaveAttribute("data-paused", "false"),
    );
    await user.click(await screen.findByRole("button", { name: "Camera wrong" }));
    expect(queue.setError).toHaveBeenCalledWith(expect.stringContaining("assigned container"));
    expect(queue.enqueue).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Camera container" }));
    expect(screen.queryByRole("checkbox", { name: /seal/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Complete audit" }));
    expect(mocks.complete).toHaveBeenCalledWith(
      "audit",
      "000000",
      true,
      false,
      expect.any(String),
      "org:actor",
      expect.any(AbortSignal),
    );
  });
  it("uses an intentional early target rescan only after missing confirmation and a sealable-only prompt", async () => {
    const user = userEvent.setup();
    queue.container = { ...queue.container!, sealable: true };
    mount();
    await user.click(screen.getByRole("button", { name: "Camera container" }));
    expect(queue.enqueue).not.toHaveBeenCalled();
    await user.click(screen.getByRole("checkbox", { name: /remaining expected/ }));
    expect(screen.getByRole("button", { name: "Complete audit" })).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: /required seal/ }));
    await user.click(screen.getByRole("button", { name: "Complete audit" }));
    expect(mocks.complete).toHaveBeenCalledWith(
      "audit",
      "000000",
      true,
      true,
      expect.any(String),
      "org:actor",
      expect.any(AbortSignal),
    );
  });
  it("holds an earlier unit report target and retains note/photos on save failure", async () => {
    const user = userEvent.setup();
    queue.audit = { ...original, scans: [scan("old")] };
    vi.mocked(queue.findingWithPhotos).mockResolvedValue(undefined);
    mount();
    await user.click(screen.getByRole("button", { name: "Details" }));
    const all = screen.getByText("All scans").nextElementSibling as HTMLElement;
    await user.click(within(all).getByRole("button", { name: "Report" }));
    await user.type(screen.getByRole("textbox", { name: "Report note" }), "Cracked plug");
    const file = new File(["photo"], "damage.png", { type: "image/png" });
    await user.upload(
      screen.getByText("Choose optional evidence photographs").querySelector("input")!,
      file,
    );
    await user.click(screen.getByRole("button", { name: "Save report" }));
    expect(screen.getByRole("textbox", { name: "Report note" })).toHaveValue("Cracked plug");
    expect(screen.getByText("1 photographs selected")).toBeInTheDocument();
    expect(queue.findingWithPhotos).toHaveBeenCalledWith(
      expect.objectContaining({
        body: expect.objectContaining({ assetId: "unit", type: "DAMAGED", note: "Cracked plug" }),
      }),
      [file],
    );
  });
  it("rejects late unit pages after the actor or audit attempt changes", async () => {
    let reply: (value: unknown) => void = () => {};
    mocks.candidates.mockReturnValue(
      new Promise((resolve) => {
        reply = resolve;
      }),
    );
    const user = userEvent.setup(),
      rendered = mount();
    await user.click(screen.getByRole("button", { name: "Details" }));
    mocks.session.principal = { organizationId: "new-org", userId: "new-actor" };
    queue = { ...queue, audit: { ...original, id: "new-audit" } };
    rendered.rerender(view());
    await act(async () =>
      reply({
        kind: "ok",
        data: {
          items: [
            {
              id: "foreign",
              displayName: "Private old unit",
              publicCode: "OLD000",
              assetModelId: "old",
              modelName: "Old",
              active: true,
            },
          ],
        },
      }),
    );
    expect(screen.queryByText(/Private old unit/)).not.toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("preserves a newer report draft when an earlier finishing comment synchronizes", async () => {
    queue.audit = {
      ...original,
      expectedRequirements: [
        { ...original.expectedRequirements[0]!, satisfied: true, matchedQuantity: 2 },
      ],
    };
    const user = userEvent.setup(),
      rendered = mount();
    await user.click(await screen.findByRole("button", { name: "Note additional contents" }));
    await user.type(screen.getByRole("textbox", { name: "Report note" }), "Extra adapters");
    queue = { ...queue, completionBlocked: true };
    rendered.rerender(view());
    await user.click(screen.getByRole("button", { name: "Save report" }));
    const command = vi.mocked(queue.findingWithPhotos).mock.calls[0]![0];
    await user.click(await screen.findByRole("button", { name: "Report" }));
    await user.type(screen.getByRole("textbox", { name: "Report note" }), "New damage draft");
    const photo = new File(["photo"], "new.png", { type: "image/png" });
    await user.upload(
      screen.getByText("Choose optional evidence photographs").querySelector("input")!,
      photo,
    );
    queue = {
      ...queue,
      completionBlocked: false,
      audit: {
        ...queue.audit!,
        findings: [
          {
            id: "extra",
            type: "UNKNOWN_CODE",
            sourceOperationId: command.body.operationId,
            detail: "{}",
          },
        ],
      },
    };
    rendered.rerender(view());
    expect(screen.getByRole("textbox", { name: "Report note" })).toHaveValue("New damage draft");
    expect(screen.getByText("1 photographs selected")).toBeInTheDocument();
    expect(screen.queryByText(/Close the container/)).not.toBeInTheDocument();
  });
  it("drops late acknowledgements and report drafts on actor changes", async () => {
    const user = userEvent.setup();
    const rendered = mount();
    await user.click(screen.getByRole("button", { name: "Camera item" }));
    const op = vi.mocked(queue.enqueue).mock.calls[0]![0];
    await user.click(screen.getByRole("button", { name: "Report" }));
    await user.type(screen.getByRole("textbox", { name: "Report note" }), "Private draft");
    mocks.session.principal = { organizationId: "other", userId: "other" };
    rendered.rerender(view());
    ack(op.kind === "scan" ? op.body.operationId : "", {
      ...original,
      scans: [scan(op.kind === "scan" ? op.body.operationId : "")],
    });
    expect(screen.queryByRole("textbox", { name: "Report note" })).not.toBeInTheDocument();
    expect(screen.queryByTestId("expected-scan-flash")).not.toBeInTheDocument();
    expect(mocks.listeners.size).toBe(1);
    rendered.unmount();
    expect(mocks.listeners.size).toBe(0);
  });
  it("orders red missing then amber partial then green complete and unscanned units before confirmed ones", async () => {
    queue.audit = {
      ...original,
      expectedRequirements: [
        {
          ...original.expectedRequirements[0]!,
          id: "partial",
          requiredQuantity: 20,
          matchedQuantity: 13,
        },
        {
          id: "missing",
          displayOrder: 1,
          type: "SPECIFIC_ASSET",
          specificAssetId: "missing-unit",
          snapshot: JSON.stringify({ assetName: "Missing exact", assetCode: "FFFFF0" }),
          satisfied: false,
          matchedQuantity: 0,
        },
        {
          ...original.expectedRequirements[0]!,
          id: "complete",
          satisfied: true,
          matchedQuantity: 2,
          snapshot: JSON.stringify({ modelName: "Complete cable" }),
        },
      ],
      scans: [scan("old")],
    };
    mocks.candidates.mockResolvedValue({
      kind: "ok",
      data: {
        items: [
          {
            id: "unit",
            displayName: "Cable 1",
            publicCode: "ABC123",
            assetModelId: "cable",
            modelName: "Cable",
            active: true,
          },
          {
            id: "unseen",
            displayName: "Cable 2",
            publicCode: "ABC124",
            assetModelId: "cable",
            modelName: "Cable",
            active: true,
          },
        ],
      },
    });
    const user = userEvent.setup();
    mount();
    await user.click(screen.getByRole("button", { name: "Details" }));
    const summaries = screen
      .getAllByRole("button")
      .filter((button) => button.classList.contains("MuiAccordionSummary-root"));
    expect(summaries.map((button) => button.textContent)).toEqual([
      expect.stringContaining("Missing exact"),
      expect.stringContaining("13/20 found"),
      expect.stringContaining("Complete cable"),
    ]);
    await user.click(summaries[1]!);
    await screen.findAllByText(/Cable 2 - ABC124/);
    const units = within(summaries[1]!.closest(".MuiAccordion-root")!).getAllByText(
      /Cable [12] - ABC12[34]/,
    );
    expect(units.map((unit) => unit.textContent)).toEqual([
      expect.stringContaining("Not yet scanned"),
      expect.stringContaining("Found"),
    ]);
  });
  it("waits for additional comment and photos synchronization before closing rescan", async () => {
    queue.audit = {
      ...original,
      expectedRequirements: [
        { ...original.expectedRequirements[0]!, satisfied: true, matchedQuantity: 2 },
      ],
    };
    const user = userEvent.setup();
    const rendered = mount();
    await user.click(await screen.findByRole("button", { name: "Note additional contents" }));
    await user.type(
      screen.getByRole("textbox", { name: "Report note" }),
      "Two spare adapters without QR labels",
    );
    queue = { ...queue, completionBlocked: true };
    rendered.rerender(view());
    await user.click(screen.getByRole("button", { name: "Save report" }));
    expect(screen.queryByText(/Close the container/)).not.toBeInTheDocument();
    const command = vi.mocked(queue.findingWithPhotos).mock.calls[0]![0];
    queue = {
      ...queue,
      completionBlocked: false,
      audit: {
        ...queue.audit!,
        findings: [
          {
            id: "comment",
            type: "UNKNOWN_CODE",
            sourceOperationId: command.body.operationId,
            note: command.body.note,
            detail: "{}",
          },
        ],
      },
    };
    rendered.rerender(view());
    expect(
      await screen.findByText(/Close the container, then scan 000000 again/),
    ).toBeInTheDocument();
    expect(screen.getByTestId("camera")).toHaveAttribute("data-paused", "false");
  });
});
