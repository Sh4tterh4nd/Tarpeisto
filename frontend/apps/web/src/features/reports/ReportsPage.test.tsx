import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { ReportsPage } from "./ReportsPage";

afterEach(() => vi.restoreAllMocks());
it("explains report scope and surfaces failed downloads without navigating away", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 403 }));
  render(<ReportsPage />);
  expect(
    screen.getByText(/Archived records and retained history are included/),
  ).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Download audit results CSV" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Verify your sign-in and try again");
  expect(fetch).toHaveBeenCalledWith("/api/v1/reports/audits.csv", { credentials: "same-origin" });
  expect(screen.getByRole("button", { name: "Download audit results CSV" })).toBeEnabled();
});
