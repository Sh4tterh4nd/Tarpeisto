import Button from "@mui/material/Button";
import Typography from "@mui/material/Typography";
import { Link as RouterLink } from "react-router-dom";
import { useSession } from "./useSession";

/**
 * Role-aware navigation/account controls (implementation plan 3.2). Hiding
 * the "Users" link from a non-Owner is UX only -- see `RequireRole`'s
 * Javadoc-equivalent comment for why this never substitutes for server-side
 * authorization.
 */
export function AccountNav() {
  const { status, principal, signOut } = useSession();

  if (status === "loading") {
    return null;
  }

  if (status === "anonymous" || status === "setup-required") {
    return (
      <Button component={RouterLink} to="/sign-in" color="inherit">
        Sign in
      </Button>
    );
  }

  return (
    <>
      {principal?.role === "OWNER" ? (
        <Button component={RouterLink} to="/admin/users" color="inherit">
          Users
        </Button>
      ) : null}
      <Typography color="inherit" variant="body2" sx={{ display: { xs: "none", sm: "block" } }}>
        {principal?.displayName}
      </Typography>
      <Button color="inherit" onClick={() => void signOut()}>
        Sign out
      </Button>
    </>
  );
}
