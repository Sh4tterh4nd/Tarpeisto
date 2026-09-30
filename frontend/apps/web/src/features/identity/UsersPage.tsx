import { useCallback, useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import FormControlLabel from "@mui/material/FormControlLabel";
import { ArchiveButton } from "../archive/ArchiveButton";
import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import IconButton from "@mui/material/IconButton";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Select, { type SelectChangeEvent } from "@mui/material/Select";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TableContainer from "@mui/material/TableContainer";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import BadgeIcon from "@mui/icons-material/Badge";
import type { AppError } from "@tarpeisto/api-client";
import { PageHeading } from "@tarpeisto/shared-ui";
import type { Role } from "./sessionApi";
import { CreateUserDialog } from "./CreateUserDialog";
import { ExternalIdentitiesDialog } from "./ExternalIdentitiesDialog";
import { ROLE_LABELS, ROLE_OPTIONS } from "./roles";
import {
  changeUserRole,
  createUser,
  listUsers,
  setUserEnabled,
  type CreateUserInput,
  type UserRecord,
} from "./usersApi";

type ListState =
  | { status: "loading" }
  | { status: "loaded"; users: UserRecord[] }
  | { status: "error"; error: AppError };

function describeActionFailure(error: AppError): string {
  // spec 4.1/28: demoting or disabling the last enabled Owner returns a
  // specific, already human-readable VALIDATION_FAILED detail ("At least
  // one enabled Owner must remain in the organization."); surface it
  // verbatim rather than a generic failure banner.
  if (error.errorCode === "VALIDATION_FAILED" && error.problem?.detail) {
    return error.problem.detail;
  }
  if (error.errorCode === "ACCESS_DENIED") {
    return "Your role no longer permits this action. Sign in again if this is unexpected.";
  }
  if (error.status === 404) {
    return "That user no longer exists.";
  }
  return error.message;
}

/**
 * Owner-only user administration: list, create, change role, enable/disable
 * (spec 4.1; implementation plan 3.2 "Owner-only role management page").
 * `RequireRole` keeps non-Owners from reaching this route client-side; every
 * request this page makes is independently authorized server-side, so a
 * `403 ACCESS_DENIED` here (for example a role changed moments earlier in
 * another tab) is handled inline rather than assumed impossible.
 */
export function UsersPage() {
  const [includeArchived, setIncludeArchived] = useState(false);
  const [state, setState] = useState<ListState>({ status: "loading" });
  const [actionError, setActionError] = useState<string | undefined>(undefined);
  const [pendingUserId, setPendingUserId] = useState<string | undefined>(undefined);
  const [createOpen, setCreateOpen] = useState(false);
  const [identitiesUser, setIdentitiesUser] = useState<UserRecord | undefined>(undefined);

  const load = useCallback(async () => {
    setState({ status: "loading" });
    const result = await listUsers(includeArchived);
    if (result.kind === "error") {
      setState({ status: "error", error: result.error });
      return;
    }
    setState({ status: "loaded", users: result.data });
  }, [includeArchived]);

  useEffect(() => {
    void (async () => {
      await load();
    })();
  }, [load]);

  async function handleRoleChange(user: UserRecord, role: Role) {
    setActionError(undefined);
    setPendingUserId(user.id);
    const error = await changeUserRole(user.id, role);
    setPendingUserId(undefined);
    if (error) {
      setActionError(describeActionFailure(error));
      return;
    }
    await load();
  }

  async function handleEnabledChange(user: UserRecord, enabled: boolean) {
    setActionError(undefined);
    setPendingUserId(user.id);
    const error = await setUserEnabled(user.id, enabled);
    setPendingUserId(undefined);
    if (error) {
      setActionError(describeActionFailure(error));
      return;
    }
    await load();
  }

  async function handleCreate(input: CreateUserInput): Promise<AppError | undefined> {
    const result = await createUser(input);
    if (result.kind === "error") {
      return result.error;
    }
    setCreateOpen(false);
    await load();
    return undefined;
  }

  return (
    <>
      <PageHeading
        title="Users"
        description="Owner-only administration for permanent Tarpeisto accounts."
        actions={
          <Button variant="contained" onClick={() => setCreateOpen(true)}>
            Create user
          </Button>
        }
      />

      <FormControlLabel
        label="Include archived"
        control={
          <Switch
            checked={includeArchived}
            onChange={(event) => setIncludeArchived(event.target.checked)}
          />
        }
      />
      <Typography variant="body2" color="text.secondary">
        Archiving disables sign-in and revokes sessions. Restored accounts remain disabled until
        enabled.
      </Typography>
      {actionError ? (
        <Alert
          severity="error"
          role="alert"
          sx={{ mb: 2 }}
          onClose={() => setActionError(undefined)}
        >
          {actionError}
        </Alert>
      ) : null}

      {state.status === "loading" ? (
        <Stack direction="row" spacing={2} sx={{ alignItems: "center", py: 4 }}>
          <CircularProgress size={24} aria-hidden="true" />
          <Typography role="status">Loading users…</Typography>
        </Stack>
      ) : null}

      {state.status === "error" ? (
        <Alert severity="error" role="alert">
          Could not load users: {state.error.message}
        </Alert>
      ) : null}

      {state.status === "loaded" ? (
        <TableContainer component={Paper} variant="outlined" sx={{ overflowX: "auto" }}>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Username</TableCell>
                <TableCell>Display name</TableCell>
                <TableCell>Email</TableCell>
                <TableCell>Role</TableCell>
                <TableCell>Enabled</TableCell>
                <TableCell>External identities</TableCell>
                <TableCell>Archive</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {state.users.map((user) => (
                <TableRow key={user.id}>
                  <TableCell>{user.username}</TableCell>
                  <TableCell>{user.displayName}</TableCell>
                  <TableCell>{user.email || "—"}</TableCell>
                  <TableCell>
                    <Select
                      value={user.role}
                      onChange={(event: SelectChangeEvent) =>
                        void handleRoleChange(user, event.target.value as Role)
                      }
                      disabled={pendingUserId === user.id || user.archived}
                      size="small"
                      aria-label={`Role for ${user.username}`}
                    >
                      {ROLE_OPTIONS.map((role) => (
                        <MenuItem key={role} value={role}>
                          {ROLE_LABELS[role]}
                        </MenuItem>
                      ))}
                    </Select>
                  </TableCell>
                  <TableCell>
                    <Switch
                      checked={user.enabled}
                      onChange={(event) => void handleEnabledChange(user, event.target.checked)}
                      disabled={pendingUserId === user.id || user.archived}
                      slotProps={{ input: { "aria-label": `Enabled for ${user.username}` } }}
                    />
                  </TableCell>
                  <TableCell>
                    <IconButton
                      aria-label={`Manage external identities for ${user.username}`}
                      disabled={user.archived}
                      onClick={() => setIdentitiesUser(user)}
                    >
                      <BadgeIcon />
                    </IconButton>
                  </TableCell>
                  <TableCell>
                    <ArchiveButton
                      kind="user"
                      id={user.id}
                      archived={user.archived ?? false}
                      version={user.version ?? 0}
                      label="user"
                      onChanged={load}
                    />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      ) : null}

      <CreateUserDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreate={handleCreate}
      />

      {identitiesUser ? (
        <ExternalIdentitiesDialog
          user={identitiesUser}
          onClose={() => setIdentitiesUser(undefined)}
        />
      ) : null}
    </>
  );
}
