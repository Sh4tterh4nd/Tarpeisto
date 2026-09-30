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

function applicationReadFixture(url: string) {
  if (url.includes("/api/v1/dashboard"))
    return {
      queues: [
        "UPCOMING_EVENTS",
        "OUTSTANDING_CUSTODY",
        "AUDITS",
        "REVIEW",
        "REPAIRS",
        "METADATA",
        "CONTAINERS",
        "LOW_STOCK",
      ].map((queue) => ({ queue, count: 0, items: [] })),
    };
  if (url.includes("/api/v1/assets") || url.includes("/api/v1/asset-models/search"))
    return { items: [], nextCursor: undefined };
  if (
    ["/api/v1/categories", "/api/v1/locations", "/api/v1/bookings"].some((path) =>
      url.includes(path),
    )
  )
    return [];
  if (url.includes("/api/v1/setup")) return { setupRequired: false };
  return { applicationName: "Tarpeisto", version: "0.1.0", oidcConfigured: false };
}

describe("App shell", () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = input instanceof Request ? input.url : String(input);
      const body = url.includes("/api/v1/session")
        ? {
            userId: "11111111-1111-1111-1111-111111111111",
            username: "owner",
            displayName: "Owner",
            organizationId: "22222222-2222-2222-2222-222222222222",
            role: "OWNER",
          }
        : applicationReadFixture(url);
      return Promise.resolve(
        new Response(JSON.stringify(body), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      );
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("renders the public status page, app title and connectivity indicator", async () => {
    render(
      <MemoryRouter initialEntries={["/status"]}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findByRole("heading", { name: "Tarpeisto" })).toBeInTheDocument();
    expect(screen.getAllByRole("status").length).toBeGreaterThan(0);
    expect(await screen.findByText("Tarpeisto", { selector: "p" })).toBeInTheDocument();
  });

  it("navigates to the continuous scanner route", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={["/"]}>
        <App />
      </MemoryRouter>,
    );

    await user.click(await screen.findByRole("link", { name: "Scan equipment" }));

    // The scanner's ZXing fallback is intentionally lazy-loaded with this route.
    expect(
      await screen.findByRole("heading", { name: "Scan equipment" }, { timeout: 5000 }),
    ).toBeInTheDocument();
  });

  it("provides separate model and asset destinations in the left navigation", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={["/"]}>
        <App />
      </MemoryRouter>,
    );
    expect(await screen.findByRole("link", { name: "Inventory models" })).toHaveAttribute(
      "href",
      "/inventory",
    );
    expect(screen.getByRole("link", { name: "Assets" })).toHaveAttribute(
      "href",
      "/inventory/assets",
    );
    await user.click(screen.getByRole("link", { name: "Assets" }));
    expect(screen.getByRole("link", { name: "Assets" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Inventory models" })).not.toHaveAttribute(
      "aria-current",
    );
  });

  it("redirects an uninitialized installation to setup and signs in the new Owner", async () => {
    vi.mocked(global.fetch).mockImplementation(async (input) => {
      const request = input instanceof Request ? input : new Request(input);
      const url = request.url;
      if (url.includes("/api/v1/session") && request.method === "GET") {
        return new Response(
          JSON.stringify({ type: "about:blank", title: "Unauthorized", status: 401 }),
          { status: 401, headers: { "content-type": "application/json" } },
        );
      }
      if (url.includes("/api/v1/setup") && request.method === "GET") {
        return new Response(JSON.stringify({ setupRequired: true }), {
          status: 200,
          headers: { "content-type": "application/json" },
        });
      }
      if (url.includes("/api/v1/setup") && request.method === "POST") {
        return new Response(null, { status: 201 });
      }
      if (url.includes("/api/v1/session") && request.method === "POST") {
        return new Response(
          JSON.stringify({
            userId: "11111111-1111-1111-1111-111111111111",
            username: "first-owner",
            displayName: "First Owner",
            organizationId: "22222222-2222-2222-2222-222222222222",
            role: "OWNER",
          }),
          { status: 200, headers: { "content-type": "application/json" } },
        );
      }
      return new Response(JSON.stringify(applicationReadFixture(url)), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    });

    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={["/inventory"]}>
        <App />
      </MemoryRouter>,
    );

    expect(
      await screen.findByRole("heading", { name: "Set up your inventory home" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("navigation", { name: "Primary navigation" }),
    ).not.toBeInTheDocument();

    await user.clear(screen.getByLabelText(/owner display name/i));
    await user.type(screen.getByLabelText(/owner display name/i), "First Owner");
    await user.type(screen.getByLabelText(/owner username/i), "first-owner");
    await user.type(screen.getByLabelText(/^password/i), "strong-password");
    await user.type(screen.getByLabelText(/confirm password/i), "strong-password");
    await user.click(screen.getByRole("button", { name: "Create organization" }));

    expect(
      await screen.findByRole("navigation", { name: "Primary navigation" }),
    ).toBeInTheDocument();
  }, 10_000);
});
