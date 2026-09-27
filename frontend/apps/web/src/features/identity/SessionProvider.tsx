import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { onUnauthenticatedResponse } from "@tarpeisto/api-client";
import { SessionContext, type SessionContextValue, type SessionStatus } from "./SessionContext";
import {
  fetchCurrentSession,
  login as loginRequest,
  logout as logoutRequest,
  type LoginCredentials,
  type SessionPrincipal,
} from "./sessionApi";

export interface SessionProviderProps {
  children: ReactNode;
}

/**
 * Restores session state from the server on mount (`GET /api/v1/session`,
 * spec 4.3) and exposes the current principal/role to the rest of the SPA.
 * Also subscribes to the shared api-client's 401 notifications so that a
 * session which expires or is revoked mid-use (for example a stale cookie
 * hit on some other page's mutation) drops the app back to the anonymous
 * state and lets route guards redirect to sign-in.
 *
 * This context is a UX convenience only. It mirrors what the server
 * reports; it never itself decides what a user may do (see `RequireRole`).
 */
export function SessionProvider({ children }: SessionProviderProps) {
  const [status, setStatus] = useState<SessionStatus>("loading");
  const [principal, setPrincipal] = useState<SessionPrincipal | undefined>(undefined);
  const [sessionExpired, setSessionExpired] = useState(false);
  const statusRef = useRef<SessionStatus>("loading");

  useEffect(() => {
    statusRef.current = status;
  }, [status]);

  const refresh = useCallback(async () => {
    const outcome = await fetchCurrentSession();
    if (outcome.kind === "authenticated") {
      setPrincipal(outcome.principal);
      setStatus("authenticated");
      setSessionExpired(false);
      return;
    }
    // Both "never signed in" and an unexpected transport error fall back to
    // the anonymous state: sign-in remains reachable either way, which is
    // what actually matters to the user (ADR-0003 anti-lockout guarantee).
    setPrincipal(undefined);
    setStatus("anonymous");
  }, []);

  useEffect(() => {
    void (async () => {
      await refresh();
    })();
  }, [refresh]);

  useEffect(
    () =>
      onUnauthenticatedResponse(() => {
        if (statusRef.current === "authenticated") {
          setSessionExpired(true);
        }
        setPrincipal(undefined);
        setStatus((current) => (current === "loading" ? current : "anonymous"));
      }),
    [],
  );

  const signIn = useCallback(async (credentials: LoginCredentials) => {
    const outcome = await loginRequest(credentials);
    if (outcome.kind === "authenticated") {
      setPrincipal(outcome.principal);
      setStatus("authenticated");
      setSessionExpired(false);
      return undefined;
    }
    return outcome.kind === "error" ? outcome.error : undefined;
  }, []);

  const signOut = useCallback(async () => {
    await logoutRequest();
    setPrincipal(undefined);
    setStatus("anonymous");
  }, []);

  const acknowledgeSessionExpired = useCallback(() => {
    setSessionExpired(false);
  }, []);

  const value = useMemo<SessionContextValue>(
    () => ({
      status,
      principal,
      role: principal?.role,
      sessionExpired,
      acknowledgeSessionExpired,
      signIn,
      signOut,
      refresh,
    }),
    [status, principal, sessionExpired, acknowledgeSessionExpired, signIn, signOut, refresh],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}
