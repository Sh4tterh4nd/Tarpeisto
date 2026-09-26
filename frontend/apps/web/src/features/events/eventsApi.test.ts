import { afterEach, describe, expect, it, vi } from "vitest";
import {
  checkInBookingAsset,
  checkoutBooking,
  completeBookingReturn,
  createBooking,
  listBookings,
  returnBookingConsumable,
} from "./eventsApi";

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

  it("uses a distinct idempotency mutation for every phase 8 command", async () => {
    const requests: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL) => {
        requests.push(input instanceof Request ? input : new Request(input));
        return Promise.resolve(
          new Response(JSON.stringify({ assets: [], consumables: [], auditTasks: [] }), {
            status: 200,
            headers: { "content-type": "application/json" },
          }),
        );
      }),
    );
    const bookingId = "11111111-1111-1111-1111-111111111111";
    await checkoutBooking(bookingId, { expectedVersion: 2, mutationId: "checkout-mutation" });
    await checkInBookingAsset(bookingId, "22222222-2222-2222-2222-222222222222", "asset-mutation");
    await returnBookingConsumable(bookingId, "33333333-3333-3333-3333-333333333333", {
      mutationId: "stock-mutation",
      quantity: 1,
      destinationLocationId: "44444444-4444-4444-4444-444444444444",
    });
    await completeBookingReturn(bookingId, "complete-mutation");

    expect(requests).toHaveLength(4);
    await expect(requests[0]!.json()).resolves.toMatchObject({ mutationId: "checkout-mutation" });
    await expect(requests[1]!.json()).resolves.toMatchObject({ mutationId: "asset-mutation" });
    await expect(requests[2]!.json()).resolves.toMatchObject({ mutationId: "stock-mutation" });
    await expect(requests[3]!.json()).resolves.toMatchObject({ mutationId: "complete-mutation" });
  });
});
