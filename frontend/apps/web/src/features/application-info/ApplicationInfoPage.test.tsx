import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApplicationInfoPage } from "./ApplicationInfoPage";

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json" },
    ...init,
  });
}

describe("ApplicationInfoPage", () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    global.fetch = vi.fn();
  });

  afterEach(() => {
    global.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it("renders the application name, version, and OIDC status once loaded", async () => {
    vi.mocked(global.fetch).mockResolvedValue(
      jsonResponse({
        applicationName: "Tarpeisto",
        version: "0.1.0",
        oidcConfigured: true,
        oidcProvider: {
          displayName: "Acme SSO",
          authorizationEndpoint: "/oauth2/authorization/oidc",
        },
      }),
    );

    render(<ApplicationInfoPage />);

    expect(await screen.findByText("Tarpeisto")).toBeInTheDocument();
    expect(screen.getByText("Version 0.1.0")).toBeInTheDocument();
    expect(screen.getByText("OIDC configured (Acme SSO)")).toBeInTheDocument();
  });

  it("renders a clear error state when the API call fails", async () => {
    vi.mocked(global.fetch).mockResolvedValue(
      jsonResponse(
        {
          type: "about:blank",
          title: "Service unavailable",
          status: 503,
          errorCode: "SERVICE_UNAVAILABLE",
        },
        { status: 503 },
      ),
    );

    render(<ApplicationInfoPage />);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Service unavailable");
  });
});
