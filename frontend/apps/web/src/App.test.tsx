import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

// vite-plugin-pwa's virtual module is only resolvable in a real Vite
// serve/build context, not under Vitest's module runner, so it is mocked
// here rather than exercised.
vi.mock("virtual:pwa-register/react", () => ({
  useRegisterSW: () => ({
    needRefresh: [false, () => {}],
    offlineReady: [false, () => {}],
    updateServiceWorker: async () => {},
  }),
}));

describe("App shell", () => {
  beforeEach(() => {
    global.fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(
          JSON.stringify({ name: "BigContainers", version: "0.1.0", oidcConfigured: false }),
          { status: 200, headers: { "content-type": "application/json" } },
        ),
      );
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("renders the app title, a connectivity indicator, and the home route", async () => {
    render(
      <MemoryRouter initialEntries={["/"]}>
        <App />
      </MemoryRouter>,
    );

    expect(screen.getByRole("heading", { name: "BigContainers" })).toBeInTheDocument();
    expect(screen.getAllByRole("status").length).toBeGreaterThan(0);
    expect(await screen.findByText("BigContainers", { selector: "p" })).toBeInTheDocument();
  });

  it("navigates to the asset-code check route", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={["/"]}>
        <App />
      </MemoryRouter>,
    );

    await user.click(screen.getByRole("link", { name: "Check code" }));

    expect(await screen.findByRole("heading", { name: "Check an asset code" })).toBeInTheDocument();
  });
});
