import type { SessionPrincipal } from "../../features/identity/sessionApi";

/** Fixed grant metadata is a local safety guard; the server still validates revocation. */
export function temporaryAccessExpired(principal: SessionPrincipal, now = Date.now()) {
  const access = principal.temporaryAccess;
  return (
    !!access &&
    (!access.expiresAt ||
      !Number.isFinite(Date.parse(access.expiresAt)) ||
      now >= Date.parse(access.expiresAt))
  );
}
