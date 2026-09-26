import { afterEach, describe, expect, it, vi } from "vitest";
import { createBooking, listBookings } from "./eventsApi";

describe("event API", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("uses bounded list parameters from the generated bookings operation", async () => {
    const requests: Request[] = [];
    const fetch = vi.fn((input: RequestInfo | URL) => {
      requests.push(input instanceof Request ? input : new Request(input));
      return;
      Promise.resolve(
        new Response("[]", { status: 200, headers: { "content-type": "application/json" } }),
      );
    });
    vi.stubGlobal("fetch", fetch);
    await listBookings("2026-10-01T00:00:00.000Z", "2026-11-01T00:00:00.000Z");
    expect(requests[0]?.url).toContain(
      "/api/v1/bookings?from=2026-10-01T00%3A00%3A00.000Z&until=2026-11-01T00%3A00%3A00.000Z&limit=100",
    );
  });

  it("sends the caller-owned mutation id unchanged for creation retries", async () => {
    const requests: Request[] = [];
    const fetch = vi.fn((input: RequestInfo | URL) => {
      requests.push(input instanceof Request ? input : new Request(input));
      return;
      Promise.resolve(
        new Response(JSON.stringify({ id: "booking-1", lines: [] }), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      );
    });
    vi.stubGlobal("fetch", fetch);
    const body = {
      mutationId: "mutation-1",
      name: "Event",
      startsAt: "2026-10-01T08:00:00Z",
      endsAt: "2026-10-01T12:00:00Z",
    };
    await createBooking(body);
    await createBooking(body);
    const first = requests[0];
    const second = requests[1];
    expect(first).toBeDefined();
    expect(second).toBeDefined();
    await expect(first!.json()).resolves.toMatchObject({ mutationId: "mutation-1" });
    await expect(second!.json()).resolves.toMatchObject({ mutationId: "mutation-1" });
  });
});
