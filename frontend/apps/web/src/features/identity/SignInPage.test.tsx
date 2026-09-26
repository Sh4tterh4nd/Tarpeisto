import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SessionProvider } from "./SessionProvider";
import { SignInPage } from "./SignInPage";

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json" },
    ...init,
  });
}

const UNAUTHENTICATED = jsonResponse(
  { type: "about:blank", title: "Unauthorized", status: 401 },
  { status: 401 },
);

function urlOf(input: unknown): string {
  return input instanceof Request ? input.url : String(input);
}

function renderSignInPage() {
  render(
    <MemoryRouter initialEntries={["/sign-in"]}>
      <SessionProvider>
        <Routes>
          <Route path="/sign-in" element={<SignInPage />} />
          <Route path="/" element={<div>Landed on home</div>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  );
}

describe("SignInPage", () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    global.fetch = vi.fn();
  });

  afterEach(() => {
    global.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it("renders the local sign-in form without an OIDC button when none is configured", async () => {
    vi.mocked(global.fetch).mockImplementation(async (input) => {
      if (urlOf(input).includes("/api/v1/application")) {
        return jsonResponse({
          applicationName: "BigContainers",
          version: "0.1.0",
          oidcConfigured: false,
        });
      }
      return UNAUTHENTICATED;
    });

    renderSignInPage();

    expect(await screen.findByLabelText("Username")).toBeInTheDocument();
    expect(screen.getByLabelText("Password")).toBeInTheDocument();
    // No OIDC button should ever appear when the server reports none configured.
    expect(screen.queryByRole("link", { name: /sign in with/i })).not.toBeInTheDocument();
  });

  it("renders the OIDC provider button as a real link when the server reports one configured", async () => {
    vi.mocked(global.fetch).mockImplementation(async (input) => {
      if (urlOf(input).includes("/api/v1/application")) {
        return jsonResponse({
          applicationName: "BigContainers",
          version: "0.1.0",
          oidcConfigured: true,
          oidcProvider: {
            displayName: "Acme SSO",
            authorizationEndpoint: "/oauth2/authorization/oidc",
          },
        });
      }
      return UNAUTHENTICATED;
    });

    renderSignInPage();

    const link = await screen.findByRole("link", { name: "Sign in with Acme SSO" });
    expect(link).toHaveAttribute("href", "/oauth2/authorization/oidc");
  });

  it("signs in successfully and leaves the sign-in route", async () => {
    vi.mocked(global.fetch).mockImplementation(async (input) => {
      const url = urlOf(input);
      const method = input instanceof Request ? input.method : "GET";
      if (url.includes("/api/v1/application")) {
        return jsonResponse({
          applicationName: "BigContainers",
          version: "0.1.0",
          oidcConfigured: false,
        });
      }
      if (url.includes("/api/v1/session") && method === "POST") {
        return jsonResponse({
          userId: "u1",
          username: "alice",
          displayName: "Alice Owner",
          organizationId: "org1",
          role: "OWNER",
        });
      }
      return UNAUTHENTICATED;
    });

    const user = userEvent.setup();
    renderSignInPage();

    await user.type(await screen.findByLabelText("Username"), "alice");
    await user.type(screen.getByLabelText("Password"), "correct-password");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByText("Landed on home")).toBeInTheDocument();
  });

  it("shows the exact backend detail for invalid credentials, identical for a wrong password or unknown user", async () => {
    vi.mocked(global.fetch).mockImplementation(async (input) => {
      const url = urlOf(input);
      const method = input instanceof Request ? input.method : "GET";
      if (url.includes("/api/v1/application")) {
        return jsonResponse({
          applicationName: "BigContainers",
          version: "0.1.0",
          oidcConfigured: false,
        });
      }
      if (url.includes("/api/v1/session") && method === "POST") {
        return jsonResponse(
          {
            type: "about:blank",
            title: "Unauthorized",
            status: 401,
            errorCode: "INVALID_CREDENTIALS",
            detail: "Invalid username or password.",
          },
          { status: 401 },
        );
      }
      return UNAUTHENTICATED;
    });

    const user = userEvent.setup();
    renderSignInPage();

    await user.type(await screen.findByLabelText("Username"), "nobody");
    await user.type(screen.getByLabelText("Password"), "whatever");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Invalid username or password.");
  });

  it("shows a rate-limit message distinct from an invalid-credentials message", async () => {
    vi.mocked(global.fetch).mockImplementation(async (input) => {
      const url = urlOf(input);
      const method = input instanceof Request ? input.method : "GET";
      if (url.includes("/api/v1/application")) {
        return jsonResponse({
          applicationName: "BigContainers",
          version: "0.1.0",
          oidcConfigured: false,
        });
      }
      if (url.includes("/api/v1/session") && method === "POST") {
        return jsonResponse(
          {
            type: "about:blank",
            title: "Too Many Requests",
            status: 429,
            errorCode: "RATE_LIMIT_EXCEEDED",
          },
          { status: 429 },
        );
      }
      return UNAUTHENTICATED;
    });

    const user = userEvent.setup();
    renderSignInPage();

    await user.type(await screen.findByLabelText("Username"), "alice");
    await user.type(screen.getByLabelText("Password"), "whatever");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/too many sign-in attempts/i);
  });

  it("shows a network-failure message when the sign-in request cannot reach the server", async () => {
    vi.mocked(global.fetch).mockImplementation(async (input) => {
      const url = urlOf(input);
      const method = input instanceof Request ? input.method : "GET";
      if (url.includes("/api/v1/application")) {
        return jsonResponse({
          applicationName: "BigContainers",
          version: "0.1.0",
          oidcConfigured: false,
        });
      }
      if (url.includes("/api/v1/session") && method === "POST") {
        throw new Error("network down");
      }
      return UNAUTHENTICATED;
    });

    const user = userEvent.setup();
    renderSignInPage();

    await user.type(await screen.findByLabelText("Username"), "alice");
    await user.type(screen.getByLabelText("Password"), "whatever");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/could not reach the server/i);
  });
});
