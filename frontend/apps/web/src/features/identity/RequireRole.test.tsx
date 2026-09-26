import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { RequireRole } from "./RequireRole";
import { SessionProvider } from "./SessionProvider";

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json" },
    ...init,
  });
}

function renderGuardedRoute(initialEntry: string) {
  render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <SessionProvider>
        <Routes>
          <Route path="/sign-in" element={<div>Sign-in page</div>} />
          <Route
            path="/admin/users"
            element={
              <RequireRole allow={["OWNER"]}>
                <div>Owner-only content</div>
              </RequireRole>
            }
          />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  );
}

describe("RequireRole", () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    global.fetch = vi.fn();
  });

  afterEach(() => {
    global.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it("redirects an anonymous visitor to sign-in instead of rendering the guarded content", async () => {
    vi.mocked(global.fetch).mockResolvedValue(
      jsonResponse({ type: "about:blank", title: "Unauthorized", status: 401 }, { status: 401 }),
    );

    renderGuardedRoute("/admin/users");

    expect(await screen.findByText("Sign-in page")).toBeInTheDocument();
    expect(screen.queryByText("Owner-only content")).not.toBeInTheDocument();
  });

  it("renders an access-denied notice for a signed-in role that is not allowed, never the guarded content", async () => {
    vi.mocked(global.fetch).mockResolvedValue(
      jsonResponse({
        userId: "u1",
        username: "vera",
        displayName: "Vera Viewer",
        organizationId: "org1",
        role: "VIEWER",
      }),
    );

    renderGuardedRoute("/admin/users");

    expect(await screen.findByRole("heading", { name: "Access denied" })).toBeInTheDocument();
    expect(screen.queryByText("Owner-only content")).not.toBeInTheDocument();
  });

  it("renders the guarded content for a signed-in Owner", async () => {
    vi.mocked(global.fetch).mockResolvedValue(
      jsonResponse({
        userId: "u1",
        username: "owen",
        displayName: "Owen Owner",
        organizationId: "org1",
        role: "OWNER",
      }),
    );

    renderGuardedRoute("/admin/users");

    expect(await screen.findByText("Owner-only content")).toBeInTheDocument();
  });
});
