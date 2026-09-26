import type { ReactNode } from "react";
import Alert from "@mui/material/Alert";
import CircularProgress from "@mui/material/CircularProgress";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { Navigate, useLocation } from "react-router-dom";
import { PageHeading } from "@bigcontainers/shared-ui";
import { useSession } from "./useSession";
import type { Role } from "./sessionApi";

export interface RequireRoleProps {
  /** Roles permitted to see this route's content. */
  allow: Role[];
  children: ReactNode;
}

/**
 * Client-side route gate (implementation plan 3.2: "Role checks for Owner,
 * Deputy, Operator/Auditor, and Viewer"; spec 4.1).
 *
 * This is UX only, never the security boundary: it decides what navigation
 * to *show*, not what the server actually permits. Every data-returning and
 * mutating endpoint enforces its own authorization server-side regardless of
 * what this component renders (spec 28: "All authorization is enforced
 * server-side"). A `403 ACCESS_DENIED` from an action this component
 * believed was reachable -- for example a role changed by another Owner in
 * another tab moments earlier -- is still handled at the call site that
 * makes the request, not prevented here.
 */
export function RequireRole({ allow, children }: RequireRoleProps) {
  const { status, role } = useSession();
  const location = useLocation();

  if (status === "loading") {
    return (
      <Stack direction="row" spacing={2} sx={{ alignItems: "center", py: 4 }}>
        <CircularProgress size={24} aria-hidden="true" />
        <Typography role="status">Loading…</Typography>
      </Stack>
    );
  }

  if (status === "anonymous") {
    return <Navigate to="/sign-in" replace state={{ from: location }} />;
  }

  if (!role || !allow.includes(role)) {
    return (
      <>
        <PageHeading title="Access denied" />
        <Alert severity="error" role="alert">
          Your role does not permit access to this page.
        </Alert>
      </>
    );
  }

  return <>{children}</>;
}
