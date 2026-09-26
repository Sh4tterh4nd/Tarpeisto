import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ExternalIdentitiesDialog } from "./ExternalIdentitiesDialog";
import type { UserRecord } from "./usersApi";

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json" },
    ...init,
  });
}

const USER: UserRecord = {
  id: "u1",
  username: "alice",
  displayName: "Alice Owner",
  email: "alice@example.com",
  enabled: true,
  role: "OWNER",
  createdAt: "2026-01-01T00:00:00Z",
};

const IDENTITY = {
  id: "ext1",
  issuer: "https://idp.example.com",
  subject: "sub-123",
  lastEmail: "alice@example.com",
  lastDisplayName: "Alice",
  createdAt: "2026-01-01T00:00:00Z",
  lastLoginAt: "2026-01-05T00:00:00Z",
  active: true,
};

function urlOf(input: unknown): string {
  return input instanceof Request ? input.url : String(input);
}

function methodOf(input: unknown): string {
  return input instanceof Request ? input.method : "GET";
}

describe("ExternalIdentitiesDialog", () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    global.fetch = vi.fn();
  });

  afterEach(() => {
    global.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it("lists a user's linked external identities", async () => {
    vi.mocked(global.fetch).mockResolvedValue(jsonResponse([IDENTITY]));

    render(<ExternalIdentitiesDialog user={USER} onClose={() => {}} />);

    expect(await screen.findByText("https://idp.example.com")).toBeInTheDocument();
    expect(screen.getByText("sub-123")).toBeInTheDocument();
  });

  it("shows a friendly empty state when no identities are linked", async () => {
    vi.mocked(global.fetch).mockResolvedValue(jsonResponse([]));

    render(<ExternalIdentitiesDialog user={USER} onClose={() => {}} />);

    expect(await screen.findByText("No linked external identities.")).toBeInTheDocument();
  });

  it("links a new external identity by issuer and subject, then reloads the list", async () => {
    let linked = false;
    vi.mocked(global.fetch).mockImplementation(async (input) => {
      const url = urlOf(input);
      const method = methodOf(input);
      if (url.includes("/external-identities") && method === "POST") {
        linked = true;
        return jsonResponse({ ...IDENTITY, id: "ext2", issuer: "https://new-idp.example.com" });
      }
      if (url.includes("/external-identities") && method === "GET") {
        return jsonResponse(
          linked ? [{ ...IDENTITY, id: "ext2", issuer: "https://new-idp.example.com" }] : [],
        );
      }
      return jsonResponse({});
    });

    const user = userEvent.setup();
    render(<ExternalIdentitiesDialog user={USER} onClose={() => {}} />);

    await screen.findByText("No linked external identities.");
    await user.type(screen.getByLabelText("Issuer"), "https://new-idp.example.com");
    await user.type(screen.getByLabelText("Subject"), "new-subject");
    await user.click(screen.getByRole("button", { name: "Link identity" }));

    expect(await screen.findByText("https://new-idp.example.com")).toBeInTheDocument();
  });

  it("unlinks an identity and reloads the list", async () => {
    let unlinked = false;
    vi.mocked(global.fetch).mockImplementation(async (input) => {
      const url = urlOf(input);
      const method = methodOf(input);
      if (url.includes(`/external-identities/${IDENTITY.id}`) && method === "DELETE") {
        unlinked = true;
        return new Response(null, { status: 200 });
      }
      if (url.includes("/external-identities") && method === "GET") {
        return jsonResponse(unlinked ? [] : [IDENTITY]);
      }
      return jsonResponse({});
    });

    const user = userEvent.setup();
    render(<ExternalIdentitiesDialog user={USER} onClose={() => {}} />);

    await screen.findByText("https://idp.example.com");
    await user.click(screen.getByLabelText(`Unlink ${IDENTITY.issuer} ${IDENTITY.subject}`));

    expect(await screen.findByText("No linked external identities.")).toBeInTheDocument();
  });

  it("surfaces the last-Owner-recovery validation message verbatim when unlinking is rejected", async () => {
    vi.mocked(global.fetch).mockImplementation(async (input) => {
      const url = urlOf(input);
      const method = methodOf(input);
      if (url.includes(`/external-identities/${IDENTITY.id}`) && method === "DELETE") {
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
      if (url.includes("/external-identities") && method === "GET") {
        return jsonResponse([IDENTITY]);
      }
      return jsonResponse({});
    });

    const user = userEvent.setup();
    render(<ExternalIdentitiesDialog user={USER} onClose={() => {}} />);

    await screen.findByText("https://idp.example.com");
    await user.click(screen.getByLabelText(`Unlink ${IDENTITY.issuer} ${IDENTITY.subject}`));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("At least one enabled Owner must remain in the organization.");
  });
});
