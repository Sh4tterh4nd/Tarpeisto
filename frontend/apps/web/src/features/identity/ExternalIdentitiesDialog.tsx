import { useCallback, useEffect, useState, type FormEvent } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import IconButton from "@mui/material/IconButton";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import DeleteIcon from "@mui/icons-material/Delete";
import type { AppError } from "@bigcontainers/api-client";
import {
  linkExternalIdentity,
  listExternalIdentities,
  unlinkExternalIdentity,
  type ExternalIdentityRecord,
  type UserRecord,
} from "./usersApi";

export interface ExternalIdentitiesDialogProps {
  user: UserRecord;
  onClose: () => void;
}

type ListState =
  | { status: "loading" }
  | { status: "loaded"; identities: ExternalIdentityRecord[] }
  | { status: "error"; error: AppError };

function describeFailure(error: AppError): string {
  // spec 4.3/28: unlinking the last authentication method of the last Owner
  // returns the same already human-readable VALIDATION_FAILED detail as the
  // last-Owner role/enabled protections; surface it verbatim.
  if (error.errorCode === "VALIDATION_FAILED" && error.problem?.detail) {
    return error.problem.detail;
  }
  if (error.errorCode === "ACCESS_DENIED") {
    return "Your role no longer permits this action.";
  }
  if (error.status === 404) {
    return "That identity no longer exists.";
  }
  return error.message;
}

/**
 * Owner-only external-identity administration for one user: list, link,
 * unlink (ADR-0003; implementation plan 3.2). The durable key is the
 * `(issuer, subject)` pair, never email (spec 4.3), so the link form asks
 * for exactly those two fields.
 */
export function ExternalIdentitiesDialog({ user, onClose }: ExternalIdentitiesDialogProps) {
  const [state, setState] = useState<ListState>({ status: "loading" });
  const [issuer, setIssuer] = useState("");
  const [subject, setSubject] = useState("");
  const [linking, setLinking] = useState(false);
  const [formError, setFormError] = useState<string | undefined>(undefined);
  const [pendingId, setPendingId] = useState<string | undefined>(undefined);

  const load = useCallback(async () => {
    setState({ status: "loading" });
    const result = await listExternalIdentities(user.id);
    if (result.kind === "error") {
      setState({ status: "error", error: result.error });
      return;
    }
    setState({ status: "loaded", identities: result.data });
  }, [user.id]);

  useEffect(() => {
    void (async () => {
      await load();
    })();
  }, [load]);

  async function handleLink(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLinking(true);
    setFormError(undefined);
    const result = await linkExternalIdentity(user.id, { issuer, subject });
    setLinking(false);
    if (result.kind === "error") {
      setFormError(describeFailure(result.error));
      return;
    }
    setIssuer("");
    setSubject("");
    await load();
  }

  async function handleUnlink(identity: ExternalIdentityRecord) {
    setFormError(undefined);
    setPendingId(identity.id);
    const error = await unlinkExternalIdentity(user.id, identity.id);
    setPendingId(undefined);
    if (error) {
      setFormError(describeFailure(error));
      return;
    }
    await load();
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>External identities for {user.displayName}</DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          {formError ? (
            <Alert severity="error" role="alert">
              {formError}
            </Alert>
          ) : null}

          {state.status === "loading" ? (
            <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
              <CircularProgress size={24} aria-hidden="true" />
              <Typography role="status">Loading…</Typography>
            </Stack>
          ) : null}

          {state.status === "error" ? (
            <Alert severity="error" role="alert">
              Could not load external identities: {state.error.message}
            </Alert>
          ) : null}

          {state.status === "loaded" ? (
            state.identities.length === 0 ? (
              <Typography color="text.secondary">No linked external identities.</Typography>
            ) : (
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Issuer</TableCell>
                    <TableCell>Subject</TableCell>
                    <TableCell>Last email</TableCell>
                    <TableCell>Unlink</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {state.identities.map((identity) => (
                    <TableRow key={identity.id}>
                      <TableCell>{identity.issuer}</TableCell>
                      <TableCell>{identity.subject}</TableCell>
                      <TableCell>{identity.lastEmail || "—"}</TableCell>
                      <TableCell>
                        <IconButton
                          aria-label={`Unlink ${identity.issuer} ${identity.subject}`}
                          onClick={() => void handleUnlink(identity)}
                          disabled={pendingId === identity.id}
                        >
                          <DeleteIcon />
                        </IconButton>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )
          ) : null}

          <Stack component="form" spacing={2} onSubmit={handleLink} noValidate>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              Link a new external identity
            </Typography>
            <TextField
              id={`link-identity-issuer-${user.id}`}
              label="Issuer"
              value={issuer}
              onChange={(event) => setIssuer(event.target.value)}
              placeholder="https://idp.example.com"
            />
            <TextField
              id={`link-identity-subject-${user.id}`}
              label="Subject"
              value={subject}
              onChange={(event) => setSubject(event.target.value)}
            />
            <Button
              type="submit"
              variant="outlined"
              disabled={linking}
              sx={{ alignSelf: "flex-start" }}
            >
              {linking ? "Linking…" : "Link identity"}
            </Button>
          </Stack>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}
