import { useState, type FormEvent } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useNavigate } from "react-router-dom";
import { PageHeading } from "@tarpeisto/shared-ui";
import { useSession } from "../identity/useSession";
import { clearInvitationToken, readInvitationToken } from "./invitationToken";
import { redeemInvitation } from "./temporaryAccessApi";
import { stopAuditIdentity } from "../../data/sync/auditIdentity";

export function VolunteerJoinPage() {
  const [token] = useState(readInvitationToken);
  const [operationId] = useState(() => crypto.randomUUID());
  const [displayName, setDisplayName] = useState("");
  const [submittedName, setSubmittedName] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const { refresh } = useSession();
  const navigate = useNavigate();
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!token || busy) return;
    const name = submittedName ?? displayName.trim();
    setSubmittedName(name);
    setBusy(true);
    setError(undefined);
    await stopAuditIdentity().catch(() => {});
    const result = await redeemInvitation(token, name, operationId);
    setBusy(false);
    if (result.kind === "error") {
      setError(result.error.problem?.detail ?? result.error.message);
      return;
    }
    clearInvitationToken();
    await refresh();
    navigate("/volunteer", { replace: true });
  }
  return (
    <Stack spacing={2} sx={{ maxWidth: 560, mx: "auto" }}>
      <PageHeading title="Join as a volunteer" />
      <Typography>
        Your name appears with your audit activity. This invitation expires 24 hours after it was
        created.
      </Typography>
      {!token ? (
        <Alert severity="warning">Open the invitation link or scan its QR code to join.</Alert>
      ) : (
        <Paper component="form" onSubmit={(event) => void submit(event)} sx={{ p: 3 }}>
          <Stack spacing={2}>
            {error ? <Alert severity="error">{error}</Alert> : null}
            <TextField
              autoFocus
              required
              label="Your display name"
              value={displayName}
              disabled={busy || submittedName !== undefined}
              onChange={(event) => setDisplayName(event.target.value)}
              slotProps={{ htmlInput: { maxLength: 100 } }}
            />
            <Button variant="contained" type="submit" disabled={busy || !displayName.trim()}>
              {busy ? "Joining…" : submittedName ? "Retry joining" : "Join audit team"}
            </Button>
          </Stack>
        </Paper>
      )}
    </Stack>
  );
}
