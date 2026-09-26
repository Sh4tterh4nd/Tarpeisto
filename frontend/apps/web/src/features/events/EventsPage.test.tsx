import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { EventsPage } from "./EventsPage";

const api = vi.hoisted(() => ({
  createBooking: vi.fn(),
  eventErrorMessage: vi.fn(() => "Could not create event"),
  listBookings: vi.fn(),
}));

vi.mock("../identity/useSession", () => ({ useSession: () => ({ role: "OWNER" }) }));
vi.mock("./eventsApi", () => api);

describe("EventsPage", () => {
  beforeEach(() => {
    api.createBooking.mockReset();
    api.listBookings.mockReset();
    api.listBookings.mockResolvedValue({ kind: "ok", data: [] });
  });

  it("reuses the generated mutation id when a failed create is retried", async () => {
    api.createBooking
      .mockResolvedValueOnce({ kind: "error", error: new Error("offline") })
      .mockResolvedValueOnce({
        kind: "ok",
        data: { id: "booking-1", lines: [] },
      });
    render(<EventsPage />, { wrapper: MemoryRouter });

    fireEvent.click(await screen.findByRole("button", { name: "New event" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Event name" }), {
      target: { value: "Soundcheck" },
    });
    const dateInputs = dialog.querySelectorAll<HTMLInputElement>('input[type="datetime-local"]');
    fireEvent.change(dateInputs[0]!, { target: { value: "2026-10-03T09:00" } });
    fireEvent.change(dateInputs[1]!, { target: { value: "2026-10-03T11:00" } });
    const form = dialog.querySelector("form");
    expect(form).not.toBeNull();
    fireEvent.submit(form!);
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not create event");

    fireEvent.submit(form!);
    await waitFor(() => expect(api.createBooking).toHaveBeenCalledTimes(2));
    const firstInput = api.createBooking.mock.calls[0]?.[0] as { mutationId?: string } | undefined;
    const retryInput = api.createBooking.mock.calls[1]?.[0] as { mutationId?: string } | undefined;
    expect(firstInput?.mutationId).toBe(retryInput?.mutationId);
  });
});
