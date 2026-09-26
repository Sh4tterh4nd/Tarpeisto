import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import { useSession } from "./useSession";

/**
 * Surfaces `SessionContext.sessionExpired` (a session that ended
 * unexpectedly mid-use -- a stale cookie observed on some other API call,
 * spec 24.2/28) as a dismissible banner. `RequireRole` already redirects
 * guarded routes back to sign-in on its own once the context flips to
 * "anonymous"; this banner only explains why that happened.
 */
export function SessionExpiredBanner() {
  const { sessionExpired, acknowledgeSessionExpired } = useSession();

  if (!sessionExpired) {
    return null;
  }

  return (
    <Alert
      severity="warning"
      role="alert"
      sx={{ borderRadius: 0 }}
      action={
        <Button color="inherit" size="small" onClick={acknowledgeSessionExpired}>
          Dismiss
        </Button>
      }
    >
      Your session ended. Sign in again to continue.
    </Alert>
  );
}
