import { useEffect, useRef, useState, type FormEvent } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Divider from "@mui/material/Divider";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import { Navigate, useLocation } from "react-router-dom";
import { apiClient } from "@tarpeisto/api-client";
import type { AppError } from "@tarpeisto/api-client";
import { PageHeading } from "@tarpeisto/shared-ui";
import { useSession } from "./useSession";

type OidcAvailability =
  | { status: "loading" }
  | { status: "unavailable" }
  | { status: "available"; displayName: string; authorizationEndpoint: string };

interface LocationState {
  from?: { pathname: string };
}

/**
 * Describes a sign-in failure for display. Spec 4.3/28: a wrong password and
 * an unknown username return byte-identical `INVALID_CREDENTIALS` text, and
 * nothing here adds a branch that could tell them apart.
 */
function describeSignInError(error: AppError): string {
  if (error.kind === "network") {
    return "Could not reach the server. Check your connection and try again.";
  }
  if (error.errorCode === "RATE_LIMIT_EXCEEDED") {
    return "Too many sign-in attempts. Wait a moment and try again.";
  }
  return error.problem?.detail ?? error.message;
}

/**
 * Local username/password sign-in (spec 4.3), plus the optional OIDC
 * provider button (ADR-0003) rendered only once `GET /api/v1/application`
 * reports one configured. The local form is never gated on that lookup
 * succeeding or on OIDC being configured at all -- that is what ADR-0003's
 * anti-lockout guarantee requires of this page.
 */
export function SignInPage() {
  const { status, principal, signIn } = useSession();
  const location = useLocation();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<AppError | undefined>(undefined);
  const [oidc, setOidc] = useState<OidcAvailability>({ status: "loading" });
  const errorRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let cancelled = false;

    async function loadOidc() {
      try {
        const { data, response } = await apiClient.GET("/api/v1/application");
        const ok = response.ok;
        if (cancelled) {
          return;
        }
        if (!ok || !data?.oidcConfigured || !data.oidcProvider?.authorizationEndpoint) {
          setOidc({ status: "unavailable" });
          return;
        }
        setOidc({
          status: "available",
          displayName: data.oidcProvider.displayName ?? "single sign-on",
          authorizationEndpoint: data.oidcProvider.authorizationEndpoint,
        });
      } catch {
        // A failed lookup here never blocks local sign-in; it only hides a
        // button that would otherwise be broken (ADR-0003 anti-lockout).
        if (!cancelled) {
          setOidc({ status: "unavailable" });
        }
      }
    }

    void loadOidc();
    return () => {
      cancelled = true;
    };
  }, []);

  if (status === "authenticated" && !principal?.temporaryAccess) {
    const state = location.state as LocationState | null;
    return <Navigate to={state?.from?.pathname ?? "/"} replace />;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(undefined);
    const outcome = await signIn({ username, password });
    setSubmitting(false);
    if (outcome) {
      setError(outcome);
      // Move focus to the error so screen-reader and keyboard users land on
      // the correction they need (policy 6.3).
      queueMicrotask(() => errorRef.current?.focus());
    }
  }

  return (
    <>
      <PageHeading title="Sign in" description="Sign in with your Tarpeisto account." />
      <Paper variant="outlined" sx={{ p: 3, maxWidth: 400 }}>
        <Stack component="form" spacing={2} onSubmit={handleSubmit} noValidate>
          <TextField
            id="sign-in-username"
            label="Username"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            autoComplete="username"
            autoFocus
          />
          <TextField
            id="sign-in-password"
            label="Password"
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
          />
          {error ? (
            <Alert severity="error" role="alert" tabIndex={-1} ref={errorRef}>
              {describeSignInError(error)}
            </Alert>
          ) : null}
          <Button
            type="submit"
            variant="contained"
            disabled={submitting}
            sx={{ alignSelf: "flex-start" }}
          >
            {submitting ? "Signing in…" : "Sign in"}
          </Button>
        </Stack>

        {oidc.status === "available" ? (
          <>
            <Divider sx={{ my: 3 }}>or</Divider>
            {/* A real link, not a click handler: this is a full-page browser
                navigation into the OIDC authorization redirect flow, never a
                fetch (ADR-0003). */}
            <Button component="a" href={oidc.authorizationEndpoint} variant="outlined" fullWidth>
              Sign in with {oidc.displayName}
            </Button>
          </>
        ) : null}
      </Paper>
    </>
  );
}
