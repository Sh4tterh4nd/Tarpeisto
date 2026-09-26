import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { EventCheckoutPanel } from "./EventCheckoutPanel";
import type { BookingRecord, CheckoutManifest } from "./eventsApi";

vi.mock("../inventory/inventoryApi", () => ({
  listAssetModels: vi.fn(),
  listAssets: vi.fn(),
  listLocations: vi.fn(),
}));
vi.mock("../scanner/scannerApi", () => ({ lookupScannedAsset: vi.fn() }));
vi.mock("./eventsApi", () => ({
  checkInBookingAsset: vi.fn(),
  checkoutBooking: vi.fn(),
  completeBookingReturn: vi.fn(),
  eventErrorMessage: vi.fn(),
  returnBookingConsumable: vi.fn(),
}));

const booking: BookingRecord = {
  id: "11111111-1111-1111-1111-111111111111",
  name: "Autumn show",
  clientText: "",
  venueText: "",
  notes: "",
  startsAt: "2026-10-01T08:00:00Z",
  endsAt: "2026-10-01T18:00:00Z",
  status: "RESERVED",
  reservationStatus: "CONFIRMED",
  currentRevisionId: "22222222-2222-2222-2222-222222222222",
  createdByUserId: "33333333-3333-3333-3333-333333333333",
  version: 3,
  lines: [],
};

const manifest: CheckoutManifest = {
  id: "44444444-4444-4444-4444-444444444444",
  bookingId: booking.id,
  bookingStatus: "CHECKED_OUT",
  checkedOutAt: "2026-10-01T08:00:00Z",
  checkedOutByUserId: booking.createdByUserId,
  auditBatchId: "",
  bookingSnapshot: {},
  assets: [
    {
      assetId: "55555555-5555-5555-5555-555555555555",
      containerAssetId: "",
      actualParentContainerAssetId: "",
      isContainer: false,
      snapshot: { modelName: "Network switch", assetCode: "7K3MXY", individualName: "1" },
      containerSnapshot: {},
      returnedAt: "",
    },
  ],
  consumables: [],
  overrides: [],
  auditTasks: [
    {
      id: "66666666-6666-6666-6666-666666666666",
      containerAssetId: "77777777-7777-7777-7777-777777777777",
      dependsOnTaskIds: ["88888888-8888-8888-8888-888888888888"],
      state: "BLOCKED",
    },
  ],
};

describe("EventCheckoutPanel", () => {
  it("makes the frozen checkout action the next step for a reserved event", () => {
    render(
      <MemoryRouter>
        <EventCheckoutPanel
          booking={booking}
          role="OPERATOR_AUDITOR"
          manifest={undefined}
          onManifest={vi.fn()}
        />
      </MemoryRouter>,
    );

    expect(screen.getByRole("button", { name: "Check out event" })).toBeEnabled();
    expect(
      screen.getByText(/freeze the checkout manifest before custody changes/i),
    ).toBeInTheDocument();
  });

  it("shows return progress and describes blocked audit work in words", () => {
    render(
      <MemoryRouter>
        <EventCheckoutPanel
          booking={{ ...booking, status: "CHECKED_OUT" }}
          role="OPERATOR_AUDITOR"
          manifest={manifest}
          onManifest={vi.fn()}
        />
      </MemoryRouter>,
    );

    expect(screen.getByText("0 / 1 assets returned")).toBeInTheDocument();
    expect(screen.getByText(/Blocked — complete 1 contained-case audit first/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Download manifest" })).toHaveAttribute(
      "href",
      `/api/v1/bookings/${booking.id}/checkout-manifest.pdf`,
    );
  });
});
