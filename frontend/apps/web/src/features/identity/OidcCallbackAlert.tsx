import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import { useLocation, useNavigate } from "react-router-dom";

/**
 * A denied OIDC login (ambiguous/unverified email, disabled account, or an
 * unexpected provider failure) redirects the browser to `/?oidcError=<code>`
 * (`OidcAuthenticationFailureHandler`, ADR-0003 4.3: "Ambiguous, missing, or
 * unverified email claims stop with a clear request for Owner assistance").
 * This surfaces that code as a readable message and strips it from the URL
 * so a page refresh does not keep re-showing it. Local sign-in is completely
 * unaffected either way (ADR-0003 anti-lockout guarantee).
 */
const MESSAGES: Record<string, string> = {
  ACCOUNT_DISABLED: "This account has been disabled. Contact an Owner for help.",
  NO_ORGANIZATION_MEMBERSHIP:
    "This account has no organization membership. Contact an Owner for help.",
  AUTO_LINKING_NOT_APPLICABLE:
    "No BigContainers account is linked to this sign-in yet. Contact an Owner to link it, or sign in locally.",
  EMAIL_MISSING:
    "The identity provider did not supply an email address, so this sign-in could not be matched automatically. Contact an Owner to link your account, or sign in locally.",
  EMAIL_NOT_VERIFIED:
    "The identity provider's email address is not verified, so this sign-in could not be matched automatically. Contact an Owner, or sign in locally.",
  NO_MATCHING_ACCOUNT:
    "No BigContainers account matches this sign-in. Contact an Owner, or sign in locally.",
  AMBIGUOUS_EMAIL_MATCH:
    "More than one BigContainers account matches this sign-in's email address. Contact an Owner, or sign in locally.",
};
const DEFAULT_MESSAGE =
  "Single sign-on could not complete. Local username/password sign-in remains available.";

export function OidcCallbackAlert() {
  const location = useLocation();
  const navigate = useNavigate();
  // Computed once, during the initial render, from whatever query string is
  // present at that moment -- deliberately not re-derived from `location`
  // afterwards, since the cleanup effect below strips the query parameter
  // right after mount and the message still needs to remain on screen.
  const [message, setMessage] = useState<string | undefined>(() => {
    const code = new URLSearchParams(location.search).get("oidcError");
    return code ? (MESSAGES[code] ?? DEFAULT_MESSAGE) : undefined;
  });

  useEffect(() => {
    // Only ever strips the one-shot `oidcError` query parameter captured by
    // the lazy `useState` initializer above; it never sets component state
    // itself, so a page refresh does not keep re-showing the same alert.
    const params = new URLSearchParams(location.search);
    if (!params.has("oidcError")) {
      return;
    }
    params.delete("oidcError");
    const nextSearch = params.toString();
    navigate(
      { pathname: location.pathname, search: nextSearch ? `?${nextSearch}` : "" },
      { replace: true },
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!message) {
    return null;
  }

  return (
    <Alert
      severity="error"
      role="alert"
      sx={{ borderRadius: 0 }}
      onClose={() => setMessage(undefined)}
    >
      {message}
    </Alert>
  );
}
