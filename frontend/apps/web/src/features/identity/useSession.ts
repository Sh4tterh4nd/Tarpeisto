import { useContext } from "react";
import { SessionContext, type SessionContextValue } from "./SessionContext";

/** Reads the current session from the nearest {@link SessionProvider}. */
export function useSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (!value) {
    throw new Error("useSession must be used within a SessionProvider.");
  }
  return value;
}
