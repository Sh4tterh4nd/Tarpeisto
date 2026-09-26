import { useState, type FormEvent } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import FormControl from "@mui/material/FormControl";
import InputLabel from "@mui/material/InputLabel";
import MenuItem from "@mui/material/MenuItem";
import Select from "@mui/material/Select";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import type { AppError } from "@bigcontainers/api-client";
import type { Role } from "./sessionApi";
import type { CreateUserInput } from "./usersApi";
import { ROLE_LABELS, ROLE_OPTIONS } from "./roles";

export interface CreateUserDialogProps {
  open: boolean;
  onClose: () => void;
  /** Resolves with an `AppError` on failure, `undefined` on success. */
  onCreate: (input: CreateUserInput) => Promise<AppError | undefined>;
}

/**
 * Owner-only "create user" modal form (implementation plan 3.2). MUI's
 * `Dialog` manages initial focus itself; the submit-error alert is placed
 * where it is announced immediately since it renders as soon as the form
 * re-renders (policy 6.3: dialogs and validation errors manage focus).
 */
export function CreateUserDialog({ open, onClose, onCreate }: CreateUserDialogProps) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<Role>("VIEWER");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  function reset() {
    setUsername("");
    setPassword("");
    setDisplayName("");
    setEmail("");
    setRole("VIEWER");
    setError(undefined);
  }

  function handleClose() {
    if (submitting) {
      return;
    }
    reset();
    onClose();
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(undefined);
    const failure = await onCreate({
      username,
      password,
      displayName,
      email: email || undefined,
      role,
    });
    setSubmitting(false);
    if (failure) {
      setError(failure.problem?.detail ?? failure.message);
      return;
    }
    reset();
  }

  return (
    <Dialog open={open} onClose={handleClose} fullWidth maxWidth="sm">
      <DialogTitle>Create user</DialogTitle>
      <Stack component="form" onSubmit={handleSubmit} noValidate>
        <DialogContent>
          <Stack spacing={2}>
            {error ? (
              <Alert severity="error" role="alert">
                {error}
              </Alert>
            ) : null}
            <TextField
              id="create-user-username"
              label="Username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="off"
              autoFocus
            />
            <TextField
              id="create-user-password"
              label="Password"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="new-password"
            />
            <TextField
              id="create-user-display-name"
              label="Display name"
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
            />
            <TextField
              id="create-user-email"
              label="Email (optional)"
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
            <FormControl>
              <InputLabel id="create-user-role-label">Role</InputLabel>
              <Select
                labelId="create-user-role-label"
                id="create-user-role"
                label="Role"
                value={role}
                onChange={(event) => setRole(event.target.value as Role)}
              >
                {ROLE_OPTIONS.map((option) => (
                  <MenuItem key={option} value={option}>
                    {ROLE_LABELS[option]}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={handleClose} disabled={submitting}>
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={submitting}>
            {submitting ? "Creating…" : "Create user"}
          </Button>
        </DialogActions>
      </Stack>
    </Dialog>
  );
}
