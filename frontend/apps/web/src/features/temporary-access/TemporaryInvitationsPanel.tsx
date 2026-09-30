import { useCallback, useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useSession } from "../identity/useSession";
import {
  createInvitation,
  listInvitations,
  revokeInvitation,
  type IssuedInvitation,
  type TemporaryInvitation,
  type InvitationScope,
} from "./temporaryAccessApi";

export function TemporaryInvitationsPanel({ bookingId, auditBatchId }: InvitationScope) {
  const { role, principal } = useSession();
  const [invitations, setInvitations] = useState<TemporaryInvitation[]>([]);
  const [issued, setIssued] = useState<IssuedInvitation>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const allowed = !principal?.temporaryAccess && (role === "OWNER" || role === "DEPUTY");
  const load = useCallback(async () => {
    if (!allowed) return;
    const result = await listInvitations({ bookingId, auditBatchId });
    if (result.kind === "ok") setInvitations(result.data);
    else setError(result.error.problem?.detail ?? result.error.message);
  }, [allowed, bookingId, auditBatchId]);
  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(timer);
  }, []);
  async function create() {
    setBusy(true);
    setError(undefined);
    const result = await createInvitation({ bookingId, auditBatchId });
    setBusy(false);
    if (result.kind === "ok") {
      setIssued(result.data);
      void load();
    } else setError(result.error.problem?.detail ?? result.error.message);
  }
  async function revoke(id: string) {
    setBusy(true);
    const result = await revokeInvitation(id);
    setBusy(false);
    if (result.kind === "ok") void load();
    else setError(result.error.problem?.detail ?? result.error.message);
  }
  if (!allowed) return null;
  return (
    <Paper sx={{ p: 2 }}>
      <Stack spacing={1.5}>
        <Typography variant="h6">Volunteer invitations</Typography>
        <Typography>
          Share one invitation with multiple volunteers. Each person enters their own name. Access
          lasts 24 hours from creation.
        </Typography>
        {error ? <Alert severity="error">{error}</Alert> : null}
        <Button
          variant="outlined"
          onClick={() => void create()}
          disabled={busy}
          sx={{ alignSelf: "start" }}
        >
          Create volunteer invitation
        </Button>
        {invitations.map((invitation) => (
          <Stack
            key={invitation.id}
            direction={{ xs: "column", sm: "row" }}
            spacing={1}
            sx={{ alignItems: { sm: "center" } }}
          >
            <Typography sx={{ flex: 1 }}>
              Created {new Date(invitation.issuedAt).toLocaleString()} ·{" "}
              {invitation.revokedAt
                ? "Revoked"
                : Date.parse(invitation.expiresAt) <= now
                  ? "Expired"
                  : `Expires ${new Date(invitation.expiresAt).toLocaleString()}`}
            </Typography>
            {!invitation.revokedAt && Date.parse(invitation.expiresAt) > now ? (
              <Button color="error" disabled={busy} onClick={() => void revoke(invitation.id)}>
                Revoke invitation
              </Button>
            ) : null}
          </Stack>
        ))}
      </Stack>
      <Dialog open={!!issued} onClose={() => setIssued(undefined)} fullWidth maxWidth="sm">
        <DialogTitle>Share volunteer invitation</DialogTitle>
        <DialogContent>
          <Stack spacing={2}>
            <Alert severity="info">
              Save or share this link now. It is only shown when the invitation is created.
            </Alert>
            {issued ? (
              <>
                <Box
                  component="img"
                  src={issued.qrCodeDataUrl}
                  alt="Volunteer invitation QR code"
                  sx={{ width: "100%", maxWidth: 320, alignSelf: "center" }}
                />
                <TextField
                  label="Invitation link"
                  value={issued.joinUrl}
                  fullWidth
                  slotProps={{ input: { readOnly: true } }}
                />
                <Button
                  onClick={() =>
                    void navigator.clipboard
                      .writeText(issued.joinUrl)
                      .catch(() => setError("Select and copy the invitation link."))
                  }
                >
                  Copy invitation link
                </Button>
              </>
            ) : null}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setIssued(undefined)}>Done</Button>
        </DialogActions>
      </Dialog>
    </Paper>
  );
}
