import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiClient } from "@tarpeisto/api-client";
import type { AppError } from "@tarpeisto/api-client";
import { SessionProvider } from "./SessionProvider";
import { useSession } from "./useSession";
import * as auditIdentity from "../../data/sync/auditIdentity";

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json" },
    ...init,
  });
}

const OWNER_SESSION = {
  userId: "u1",
  username: "alice",
  displayName: "Alice Owner",
  organizationId: "org1",
  role: "OWNER",
};

const UNAUTHORIZED = { type: "about:blank", title: "Unauthorized", status: 401 };

function methodOf(input: unknown): string {
  return input instanceof Request ? input.method : "GET";
}

describe("SessionProvider / useSession", () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    global.fetch = vi.fn();
  });

  afterEach(() => {
    global.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it("restores an authenticated session from GET /api/v1/session on mount", async () => {
    vi.mocked(global.fetch).mockResolvedValue(jsonResponse(OWNER_SESSION));

    const { result } = renderHook(() => useSession(), { wrapper: SessionProvider });

    expect(result.current.status).toBe("loading");
    await waitFor(() => expect(result.current.status).toBe("authenticated"));
    expect(result.current.principal?.username).toBe("alice");
    expect(result.current.role).toBe("OWNER");
  });

  it("lets external logout win while an authenticated response verifies its saved audit identity", async () => {
    vi.mocked(global.fetch).mockImplementation(async () => jsonResponse(OWNER_SESSION));
    const { result } = renderHook(() => useSession(), { wrapper: SessionProvider });
    await waitFor(() => expect(result.current.status).toBe("authenticated"));
    let finish!: (value?: void) => void;
    const verify = vi.spyOn(auditIdentity, "verifyAuditIdentity").mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    const pending = result.current.refresh();
    await waitFor(() => expect(verify).toHaveBeenCalled());
    await act(async () => {
      await auditIdentity.stopAuditIdentity().catch(() => {});
    });
    await act(async () => {
      finish();
      await pending;
    });
    expect(result.current.status).toBe("anonymous");
    expect(result.current.principal).toBeUndefined();
  });

  it("never installs an already expired temporary principal from a late server response", async () => {
    vi.mocked(global.fetch).mockResolvedValue(
      jsonResponse({
        ...OWNER_SESSION,
        role: "OPERATOR_AUDITOR",
        temporaryAccess: { sessionId: "s", invitationId: "i", expiresAt: "2000-01-01T00:00:00Z" },
      }),
    );
    const { result } = renderHook(() => useSession(), { wrapper: SessionProvider });
    await waitFor(() => expect(result.current.status).toBe("anonymous"));
    expect(result.current.principal).toBeUndefined();
  });

  it("keeps a restored session on transient foreground verification failure", async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce(jsonResponse(OWNER_SESSION));
    const { result } = renderHook(() => useSession(), { wrapper: SessionProvider });
    await waitFor(() => expect(result.current.status).toBe("authenticated"));
    vi.mocked(global.fetch).mockRejectedValueOnce(new TypeError("Connection interrupted"));
    await act(async () => {
      await result.current.refresh();
    });
    expect(result.current.status).toBe("authenticated");
    expect(result.current.principal?.userId).toBe("u1");
    vi.mocked(global.fetch).mockResolvedValueOnce(
      jsonResponse({ ...OWNER_SESSION, role: "DEPUTY" }),
    );
    await act(async () => {
      window.dispatchEvent(new Event("focus"));
    });
    await waitFor(() => expect(result.current.role).toBe("DEPUTY"));
  });

  it("does not restore an old in-flight refresh after logout", async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce(jsonResponse(OWNER_SESSION));
    const { result } = renderHook(() => useSession(), { wrapper: SessionProvider });
    await waitFor(() => expect(result.current.status).toBe("authenticated"));
    let resolveOld!: (response: Response) => void;
    vi.mocked(global.fetch).mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          resolveOld = resolve;
        }),
    );
    const pending = result.current.refresh();
    await waitFor(() => expect(resolveOld).toBeDefined());
    vi.mocked(global.fetch).mockResolvedValueOnce(new Response(null, { status: 204 }));
    await act(async () => {
      await result.current.signOut();
    });
    await act(async () => {
      resolveOld(jsonResponse(OWNER_SESSION));
      await pending;
    });
    expect(result.current.status).toBe("anonymous");
    expect(result.current.principal).toBeUndefined();
  });

  it("does not overwrite a newly signed-in actor with an older refresh", async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce(jsonResponse(OWNER_SESSION));
    const { result } = renderHook(() => useSession(), { wrapper: SessionProvider });
    await waitFor(() => expect(result.current.status).toBe("authenticated"));
    let resolveOld!: (response: Response) => void;
    vi.mocked(global.fetch).mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          resolveOld = resolve;
        }),
    );
    const pending = result.current.refresh();
    await waitFor(() => expect(resolveOld).toBeDefined());
    vi.mocked(global.fetch).mockResolvedValueOnce(
      jsonResponse({ ...OWNER_SESSION, userId: "u2", username: "bob", role: "DEPUTY" }),
    );
    await act(async () => {
      await result.current.signIn({ username: "bob", password: "secret" });
    });
    await act(async () => {
      resolveOld(jsonResponse(OWNER_SESSION));
      await pending;
    });
    expect(result.current.status).toBe("authenticated");
    expect(result.current.principal?.userId).toBe("u2");
  });

  it("falls back to the anonymous state when GET /api/v1/session returns 401", async () => {
    vi.mocked(global.fetch).mockResolvedValue(jsonResponse(UNAUTHORIZED, { status: 401 }));

    const { result } = renderHook(() => useSession(), { wrapper: SessionProvider });

    await waitFor(() => expect(result.current.status).toBe("anonymous"));
    expect(result.current.principal).toBeUndefined();
    expect(result.current.role).toBeUndefined();
  });

  it("falls back to the anonymous state (never stuck loading) on an unexpected transport error", async () => {
    vi.mocked(global.fetch).mockRejectedValue(new Error("boom"));

    const { result } = renderHook(() => useSession(), { wrapper: SessionProvider });

    // ADR-0003 anti-lockout: sign-in must always remain reachable, even when
    // session restoration itself fails in an unexpected way.
    await waitFor(() => expect(result.current.status).toBe("anonymous"));
  });

  it("signs in successfully and exposes the returned principal", async () => {
    vi.mocked(global.fetch).mockImplementation(async (input) => {
      if (methodOf(input) === "GET") {
        return jsonResponse(UNAUTHORIZED, { status: 401 });
      }
      return jsonResponse({
        userId: "u2",
        username: "bob",
        displayName: "Bob Deputy",
        organizationId: "org1",
        role: "DEPUTY",
      });
    });

    const { result } = renderHook(() => useSession(), { wrapper: SessionProvider });
    await waitFor(() => expect(result.current.status).toBe("anonymous"));

    let outcome: AppError | undefined;
    await act(async () => {
      outcome = await result.current.signIn({ username: "bob", password: "secret" });
    });

    expect(outcome).toBeUndefined();
    expect(result.current.status).toBe("authenticated");
    expect(result.current.principal?.username).toBe("bob");
    expect(result.current.role).toBe("DEPUTY");
  });

  it("returns a typed AppError for invalid credentials without granting a session", async () => {
    vi.mocked(global.fetch).mockImplementation(async (input) => {
      if (methodOf(input) === "GET") {
        return jsonResponse(UNAUTHORIZED, { status: 401 });
      }
      // Spec 4.3/28: byte-identical for a wrong password and an unknown user.
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
    });

    const { result } = renderHook(() => useSession(), { wrapper: SessionProvider });
    await waitFor(() => expect(result.current.status).toBe("anonymous"));

    let outcome: AppError | undefined;
    await act(async () => {
      outcome = await result.current.signIn({ username: "eve", password: "wrong" });
    });

    expect(outcome?.errorCode).toBe("INVALID_CREDENTIALS");
    expect(outcome?.problem?.detail).toBe("Invalid username or password.");
    expect(result.current.status).toBe("anonymous");
    expect(result.current.principal).toBeUndefined();
  });

  it("returns a typed AppError when rate limited", async () => {
    vi.mocked(global.fetch).mockImplementation(async (input) => {
      if (methodOf(input) === "GET") {
        return jsonResponse(UNAUTHORIZED, { status: 401 });
      }
      return jsonResponse(
        {
          type: "about:blank",
          title: "Too Many Requests",
          status: 429,
          errorCode: "RATE_LIMIT_EXCEEDED",
        },
        { status: 429 },
      );
    });

    const { result } = renderHook(() => useSession(), { wrapper: SessionProvider });
    await waitFor(() => expect(result.current.status).toBe("anonymous"));

    let outcome: AppError | undefined;
    await act(async () => {
      outcome = await result.current.signIn({ username: "alice", password: "whatever" });
    });

    expect(outcome?.errorCode).toBe("RATE_LIMIT_EXCEEDED");
    expect(result.current.status).toBe("anonymous");
  });

  it("signs out and clears the principal", async () => {
    vi.mocked(global.fetch).mockImplementation(async (input) => {
      if (methodOf(input) === "DELETE") {
        return new Response(null, { status: 204 });
      }
      return jsonResponse(OWNER_SESSION);
    });

    const { result } = renderHook(() => useSession(), { wrapper: SessionProvider });
    await waitFor(() => expect(result.current.status).toBe("authenticated"));

    await act(async () => {
      await result.current.signOut();
    });

    expect(result.current.status).toBe("anonymous");
    expect(result.current.principal).toBeUndefined();
  });

  it("flags sessionExpired and drops to anonymous when some other call observes a 401", async () => {
    let callCount = 0;
    vi.mocked(global.fetch).mockImplementation(async () => {
      callCount += 1;
      if (callCount === 1) {
        return jsonResponse(OWNER_SESSION);
      }
      return jsonResponse(UNAUTHORIZED, { status: 401 });
    });

    const { result } = renderHook(() => useSession(), { wrapper: SessionProvider });
    await waitFor(() => expect(result.current.status).toBe("authenticated"));
    expect(result.current.sessionExpired).toBe(false);

    // A stale cookie hit on some unrelated API call, not a call this context
    // itself made -- this is exactly the scenario the shared api-client's
    // `onUnauthenticatedResponse` hook exists for.
    await act(async () => {
      await apiClient.GET("/api/v1/application");
    });

    await waitFor(() => expect(result.current.sessionExpired).toBe(true));
    expect(result.current.status).toBe("anonymous");

    act(() => {
      result.current.acknowledgeSessionExpired();
    });
    expect(result.current.sessionExpired).toBe(false);
  });
});
