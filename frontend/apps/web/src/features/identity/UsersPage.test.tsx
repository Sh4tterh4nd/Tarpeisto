import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { UsersPage } from "./UsersPage";

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json" },
    ...init,
  });
}

const ALICE = {
  id: "u1",
  username: "alice",
  displayName: "Alice Owner",
  email: "alice@example.com",
  enabled: true,
  role: "OWNER",
  createdAt: "2026-01-01T00:00:00Z",
};

const BOB = {
  id: "u2",
  username: "bob",
  displayName: "Bob Deputy",
  email: "",
  enabled: true,
  role: "DEPUTY",
  createdAt: "2026-01-02T00:00:00Z",
};

function urlOf(input: unknown): string {
  return input instanceof Request ? input.url : String(input);
}

function methodOf(input: unknown): string {
  return input instanceof Request ? input.method : "GET";
}

describe("UsersPage", () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    global.fetch = vi.fn();
  });

  afterEach(() => {
    global.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it("loads and lists users", async () => {
    vi.mocked(global.fetch).mockImplementation(async (input) => {
      if (urlOf(input).includes("/api/v1/users") && methodOf(input) === "GET") {
        return jsonResponse([ALICE, BOB]);
      }
      return jsonResponse({});
    });

    render(<UsersPage />);

    expect(await screen.findByText("alice")).toBeInTheDocument();
    expect(screen.getByText("bob")).toBeInTheDocument();
    expect(screen.getByText("Bob Deputy")).toBeInTheDocument();
  });

  it("changes a user's role and reloads the list", async () => {
    let currentRole = "DEPUTY";
    vi.mocked(global.fetch).mockImplementation(async (input) => {
      const url = urlOf(input);
      const method = methodOf(input);
      if (url.includes("/role")) {
        currentRole = "VIEWER";
        return new Response(null, { status: 200 });
      }
      if (url.includes("/api/v1/users") && method === "GET") {
        return jsonResponse([ALICE, { ...BOB, role: currentRole }]);
      }
      return jsonResponse({});
    });

    const user = userEvent.setup();
    render(<UsersPage />);

    await screen.findByText("bob");
    await user.click(screen.getByLabelText("Role for bob"));
    await user.click(await screen.findByRole("option", { name: "Viewer" }));

    // The role select for bob now reflects the reloaded VIEWER role.
    expect(await screen.findByLabelText("Role for bob")).toHaveTextContent("Viewer");
  });

  it("surfaces the last-Owner validation message verbatim instead of a generic failure", async () => {
    vi.mocked(global.fetch).mockImplementation(async (input) => {
      const url = urlOf(input);
      const method = methodOf(input);
      if (url.includes("/role")) {
        return jsonResponse(
          {
            type: "about:blank",
            title: "Validation failed",
            status: 400,
            errorCode: "VALIDATION_FAILED",
            detail: "At least one enabled Owner must remain in the organization.",
          },
          { status: 400 },
        );
      }
      if (url.includes("/api/v1/users") && method === "GET") {
        return jsonResponse([ALICE, BOB]);
      }
      return jsonResponse({});
    });

    const user = userEvent.setup();
    render(<UsersPage />);

    await screen.findByText("alice");
    await user.click(screen.getByLabelText("Role for alice"));
    await user.click(await screen.findByRole("option", { name: "Viewer" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("At least one enabled Owner must remain in the organization.");
  });

  it("creates a user through the dialog and reloads the list", async () => {
    let created = false;
    vi.mocked(global.fetch).mockImplementation(async (input) => {
      const url = urlOf(input);
      const method = methodOf(input);
      if (url.includes("/api/v1/users") && method === "POST") {
        created = true;
        return jsonResponse({
          id: "u3",
          username: "carol",
          displayName: "Carol Viewer",
          email: "",
          enabled: true,
          role: "VIEWER",
          createdAt: "2026-01-03T00:00:00Z",
        });
      }
      if (url.includes("/api/v1/users") && method === "GET") {
        return jsonResponse(
          created ? [ALICE, BOB, { ...ALICE, id: "u3", username: "carol" }] : [ALICE, BOB],
        );
      }
      return jsonResponse({});
    });

    const user = userEvent.setup();
    render(<UsersPage />);

    await screen.findByText("alice");
    await user.click(screen.getByRole("button", { name: "Create user" }));

    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByLabelText("Username"), "carol");
    await user.type(within(dialog).getByLabelText("Password"), "s3cret-pass");
    await user.type(within(dialog).getByLabelText("Display name"), "Carol Viewer");
    await user.click(within(dialog).getByRole("button", { name: "Create user" }));

    expect(await screen.findByText("carol")).toBeInTheDocument();
  });
});
