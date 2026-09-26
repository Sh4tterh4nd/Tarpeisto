import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { ConnectivityChip } from "./ConnectivityChip";

function setOnline(value: boolean) {
  Object.defineProperty(window.navigator, "onLine", { value, configurable: true });
}

describe("ConnectivityChip", () => {
  afterEach(() => {
    setOnline(true);
  });

  it("shows Online when the browser reports connectivity", () => {
    setOnline(true);
    render(<ConnectivityChip />);

    expect(screen.getByRole("status")).toHaveTextContent("Online");
  });

  it("shows Offline and reacts to the offline event", () => {
    setOnline(true);
    render(<ConnectivityChip />);

    setOnline(false);
    act(() => {
      window.dispatchEvent(new Event("offline"));
    });

    expect(screen.getByRole("status")).toHaveTextContent("Offline");
  });
});
