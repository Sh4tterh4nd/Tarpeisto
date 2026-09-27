import { createContext } from "react";
import type { AppError } from "@tarpeisto/api-client";
import type { LoginCredentials, Role, SessionPrincipal } from "./sessionApi";

export type SessionStatus = "loading" | "authenticated" | "anonymous";

export interface SessionContextValue {
  status: SessionStatus;
  /** Defined only when `status === "authenticated"`. */
  principal: SessionPrincipal | undefined;
  /** Convenience accessor for `principal?.role`. */
  role: Role | undefined;
  /** True when the session ended unexpectedly (a stale/expired cookie observed on some other API call), distinct from never having signed in. */
  sessionExpired: boolean;
  acknowledgeSessionExpired: () => void;
  /** Resolves with an `AppError` on failure, `undefined` on success. */
  signIn: (credentials: LoginCredentials) => Promise<AppError | undefined>;
  signOut: () => Promise<void>;
  /** Re-checks the current session against the server. */
  refresh: () => Promise<void>;
}

export const SessionContext = createContext<SessionContextValue | undefined>(undefined);
