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
import { fetchInitialSetupStatus } from "../setup/setupApi";
import {
  recoverAuditIdentity,
  stopAuditIdentity,
  subscribeAuditIdentityStop,
  verifyAuditIdentity,
  disableAuditRecovery,
} from "../../data/sync/auditIdentity";
import { webConnectivityCapability } from "../../platform/web/webConnectivityCapability";
import { temporaryAccessExpired } from "../../data/sync/temporaryDeadline";

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
  const [offlineAuditTaskId, setOfflineAuditTaskId] = useState<string>();
  const statusRef = useRef<SessionStatus>("loading");
  const identityEpoch = useRef(0);
  const refreshSequence = useRef(0);
  const verifyingIdentity = useRef(false);

  useEffect(() => {
    statusRef.current = status;
  }, [status]);

  const refresh = useCallback(async () => {
    const epoch = identityEpoch.current;
    const sequence = ++refreshSequence.current;
    const current = () => epoch === identityEpoch.current && sequence === refreshSequence.current;
    const outcome = await fetchCurrentSession();
    if (!current()) return;
    if (outcome.kind === "authenticated") {
      if (temporaryAccessExpired(outcome.principal)) {
        await stopAuditIdentity().catch(() => {});
        return;
      }
      verifyingIdentity.current = true;
      try {
        await verifyAuditIdentity(outcome.principal).catch(() => {});
      } finally {
        verifyingIdentity.current = false;
      }
      if (!current()) return;
      setOfflineAuditTaskId(undefined);
      setPrincipal(outcome.principal);
      setStatus("authenticated");
      setSessionExpired(false);
      return;
    }
    if (outcome.kind === "error" && outcome.error.kind === "network") {
      if (statusRef.current === "authenticated" || statusRef.current === "offline-audit") return;
      const recovered = await recoverAuditIdentity().catch(() => undefined);
      if (!current()) return;
      if (recovered) {
        setPrincipal(recovered.principal);
        setOfflineAuditTaskId(recovered.taskId);
        setStatus("offline-audit");
        return;
      }
    }
    if (outcome.kind === "anonymous") {
      const setupStatus = await fetchInitialSetupStatus();
      if (!current()) return;
      if (setupStatus?.setupRequired) {
        setPrincipal(undefined);
        setStatus("setup-required");
        return;
      }
    }
    // A completed installation without a session, and unexpected transport
    // failures, both fall back to sign-in so local recovery stays reachable.
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
        if (statusRef.current === "loading" || statusRef.current === "setup-required") {
          void disableAuditRecovery().catch(() => {});
          return;
        }
        void stopAuditIdentity().catch(() => {});
        if (statusRef.current === "authenticated") {
          setSessionExpired(true);
        }
        setPrincipal(undefined);
        setStatus((current) =>
          current === "loading" || current === "setup-required" ? current : "anonymous",
        );
      }),
    [],
  );
  useEffect(
    () =>
      subscribeAuditIdentityStop((reason) => {
        if (verifyingIdentity.current && reason === "account-switch") return;
        identityEpoch.current++;
        setPrincipal(undefined);
        setOfflineAuditTaskId(undefined);
        setStatus("anonymous");
      }),
    [],
  );
  useEffect(
    () =>
      webConnectivityCapability.subscribe(() => {
        if (
          (statusRef.current === "offline-audit" || statusRef.current === "authenticated") &&
          webConnectivityCapability.getStatus() === "online"
        )
          void refresh();
      }),
    [refresh],
  );

  useEffect(() => {
    const foreground = () => {
      if (
        document.visibilityState === "visible" &&
        (statusRef.current === "authenticated" || statusRef.current === "offline-audit")
      )
        void refresh();
    };
    window.addEventListener("focus", foreground);
    window.addEventListener("pageshow", foreground);
    document.addEventListener("visibilitychange", foreground);
    const timer = setInterval(foreground, 5 * 60_000);
    return () => {
      clearInterval(timer);
      window.removeEventListener("focus", foreground);
      window.removeEventListener("pageshow", foreground);
      document.removeEventListener("visibilitychange", foreground);
    };
  }, [refresh]);

  useEffect(() => {
    if (!principal?.temporaryAccess) return;
    const expire = () => {
      if (temporaryAccessExpired(principal)) {
        setSessionExpired(true);
        void stopAuditIdentity().catch(() => {});
      }
    };
    const remaining = Date.parse(principal.temporaryAccess.expiresAt ?? "") - Date.now();
    const timer = setTimeout(expire, Math.max(0, Number.isFinite(remaining) ? remaining : 0));
    return () => clearTimeout(timer);
  }, [principal]);

  const signIn = useCallback(async (credentials: LoginCredentials) => {
    const epoch = ++identityEpoch.current;
    const outcome = await loginRequest(credentials);
    if (epoch !== identityEpoch.current)
      return outcome.kind === "error" ? outcome.error : undefined;
    if (outcome.kind === "authenticated") {
      if (temporaryAccessExpired(outcome.principal)) {
        await stopAuditIdentity().catch(() => {});
        return undefined;
      }
      verifyingIdentity.current = true;
      try {
        await verifyAuditIdentity(outcome.principal).catch(() => {});
      } finally {
        verifyingIdentity.current = false;
      }
      if (epoch !== identityEpoch.current) return undefined;
      setOfflineAuditTaskId(undefined);
      setPrincipal(outcome.principal);
      setStatus("authenticated");
      setSessionExpired(false);
      return undefined;
    }
    return outcome.kind === "error" ? outcome.error : undefined;
  }, []);

  const signOut = useCallback(async () => {
    identityEpoch.current++;
    setPrincipal(undefined);
    setOfflineAuditTaskId(undefined);
    setStatus("anonymous");
    await stopAuditIdentity().catch(() => {});
    await logoutRequest();
  }, []);

  const acknowledgeSessionExpired = useCallback(() => {
    setSessionExpired(false);
  }, []);

  const value = useMemo<SessionContextValue>(
    () => ({
      status,
      principal,
      offlineAuditTaskId,
      role: principal?.role,
      sessionExpired,
      acknowledgeSessionExpired,
      signIn,
      signOut,
      refresh,
    }),
    [
      status,
      principal,
      offlineAuditTaskId,
      sessionExpired,
      acknowledgeSessionExpired,
      signIn,
      signOut,
      refresh,
    ],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}
