import { afterEach, describe, expect, it, vi } from "vitest";
import { completeAudit, getAuditTask, scanAudit, startAudit } from "./auditApi";

describe("audit API", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("uses the task route for an online audit read", async () => {
    const requests: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        requests.push(
          input instanceof Request
            ? input
            : new Request(new URL(input.toString(), window.location.origin), init),
        );
        return Promise.resolve(
          new Response(
            JSON.stringify({
              expectedRequirements: [],
              scans: [],
              findings: [],
              blockingReasons: [],
            }),
            { status: 200 },
          ),
        );
      }),
    );

    await getAuditTask("task-1");

    expect(requests[0]?.url).toContain("/api/v1/audits/tasks/task-1");
    expect(requests[0]?.method).toBe("GET");
  });

  it("sends the start-container code in the online start request", async () => {
    const requests: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        requests.push(
          input instanceof Request
            ? input
            : new Request(new URL(input.toString(), window.location.origin), init),
        );
        return Promise.resolve(
          new Response(
            JSON.stringify({
              expectedRequirements: [],
              scans: [],
              findings: [],
              blockingReasons: [],
            }),
            { status: 200 },
          ),
        );
      }),
    );

    await startAudit("task-1", "000000");

    expect(requests[0]?.method).toBe("POST");
    await expect(requests[0]?.json()).resolves.toEqual({ containerCode: "000000" });
  });

  it("keeps a caller-provided operation id for explicit retries", async () => {
    const requests: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        requests.push(
          input instanceof Request
            ? input
            : new Request(new URL(input.toString(), window.location.origin), init),
        );
        return Promise.resolve(
          new Response(JSON.stringify({ detail: "Connection interrupted" }), { status: 503 }),
        );
      }),
    );

    await scanAudit("audit-1", "000000", "11111111-1111-1111-1111-111111111111");
    await scanAudit("audit-1", "000000", "11111111-1111-1111-1111-111111111111");

    await expect(requests[0]?.json()).resolves.toMatchObject({
      operationId: "11111111-1111-1111-1111-111111111111",
    });
    await expect(requests[1]?.json()).resolves.toMatchObject({
      operationId: "11111111-1111-1111-1111-111111111111",
    });
  });

  it("sends an explicit seal confirmation with the stable completion operation", async () => {
    const requests: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        requests.push(
          input instanceof Request
            ? input
            : new Request(new URL(input.toString(), window.location.origin), init),
        );
        return Promise.resolve(
          new Response(JSON.stringify({ scans: [], findings: [] }), { status: 200 }),
        );
      }),
    );

    await completeAudit("audit-1", "000000", false, true, "11111111-1111-1111-1111-111111111111");

    await expect(requests[0]?.json()).resolves.toMatchObject({
      operationId: "11111111-1111-1111-1111-111111111111",
      sealConfirmed: true,
    });
  });
});
