import { useEffect, useRef, useState } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Divider from "@mui/material/Divider";
import Link from "@mui/material/Link";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { Link as RouterLink } from "react-router-dom";
import {
  closeAssetRepair,
  createAssetReplacement,
  errorMessage,
  listSealHistory,
  openAssetRepair,
  type AssetRecord,
  type RepairRecord,
  type SealHistoryRecord,
  type AssetHistoryRecord,
} from "./inventoryApi";

export function AssetRepairDialog({
  asset,
  repairs,
  mode,
  stateHistory = [],
  onSaved,
  onClose,
}: {
  asset: AssetRecord;
  repairs: RepairRecord[];
  mode: "open" | "manage" | "view";
  stateHistory?: AssetHistoryRecord[];
  onSaved: () => Promise<void>;
  onClose: () => void;
}) {
  const [reference, setReference] = useState("");
  const [replacementName, setReplacementName] = useState("");
  const [replacement, setReplacement] = useState<AssetRecord>();
  const [resultCondition, setResultCondition] = useState<Record<string, "GOOD" | "DAMAGED">>({});
  const [history, setHistory] = useState<SealHistoryRecord[]>([]);
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  useEffect(() => {
    let current = true;
    void listSealHistory(asset.id).then((result) => {
      if (!current) return;
      if (result.kind === "ok") setHistory(result.data);
      else setError(errorMessage(result.error));
    });
    return () => {
      current = false;
    };
  }, [asset.id]);
  async function open() {
    if (busy || !reference.trim()) return;
    setBusy(true);
    setError(undefined);
    const result = await openAssetRepair(asset.id, reference.trim());
    if (!alive.current) return;
    setBusy(false);
    if (result.kind === "error") {
      setError(errorMessage(result.error));
      return;
    }
    await onSaved();
    if (alive.current) onClose();
  }
  async function close(id: string) {
    if (busy) return;
    setBusy(true);
    setError(undefined);
    const result = await closeAssetRepair(id, resultCondition[id] ?? "GOOD");
    if (!alive.current) return;
    setBusy(false);
    if (result.kind === "error") {
      setError(errorMessage(result.error));
      return;
    }
    await onSaved();
  }
  async function replace() {
    if (busy) return;
    setBusy(true);
    setError(undefined);
    const result = await createAssetReplacement(asset.id, replacementName.trim() || undefined);
    if (!alive.current) return;
    setBusy(false);
    if (result.kind === "error") {
      setError(errorMessage(result.error));
      return;
    }
    setReplacement(result.data);
    setReplacementName("");
    await onSaved();
  }
  return (
    <Dialog
      open
      onClose={() => {
        if (!busy) onClose();
      }}
      fullWidth
      maxWidth="sm"
    >
      <DialogTitle>
        {mode === "open" ? "Open repair" : mode === "view" ? "History" : "Repairs and history"}
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {error ? <Alert severity="error">{error}</Alert> : null}
          {mode === "open" ? (
            <>
              <TextField
                autoFocus
                label="Repair reference or description"
                value={reference}
                onChange={(event) => setReference(event.target.value)}
                multiline
                minRows={2}
              />
              <Typography variant="body2" color="text.secondary">
                An open repair prevents reservation and checkout.
              </Typography>
            </>
          ) : (
            <>
              {repairs.length ? (
                repairs.map((repair) => (
                  <Stack key={repair.id} spacing={1}>
                    <Typography>{repair.referenceOrDescription}</Typography>
                    <Typography variant="body2" color="text.secondary">
                      {repair.closedAt
                        ? `Closed ${new Date(repair.closedAt).toLocaleString()} (${repair.resultingCondition.toLowerCase()})`
                        : `Opened ${new Date(repair.openedAt).toLocaleString()}`}
                    </Typography>
                    {!repair.closedAt && mode === "manage" ? (
                      <Stack direction={{ xs: "column", sm: "row" }} spacing={1}>
                        <TextField
                          select
                          label="Resulting condition"
                          value={resultCondition[repair.id] ?? "GOOD"}
                          onChange={(event) =>
                            setResultCondition((current) => ({
                              ...current,
                              [repair.id]: event.target.value as "GOOD" | "DAMAGED",
                            }))
                          }
                        >
                          <MenuItem value="GOOD">Good</MenuItem>
                          <MenuItem value="DAMAGED">Damaged</MenuItem>
                        </TextField>
                        <Button disabled={busy} onClick={() => void close(repair.id)}>
                          Close repair
                        </Button>
                      </Stack>
                    ) : null}
                  </Stack>
                ))
              ) : (
                <Typography color="text.secondary">No repair history.</Typography>
              )}
              {asset.replacesAssetId ? (
                <Link component={RouterLink} to={`/inventory/assets/${asset.replacesAssetId}`}>
                  View replaced asset
                </Link>
              ) : null}
              {mode === "manage" &&
              (asset.lifecycleState === "LOST" || asset.lifecycleState === "DESTROYED") ? (
                <>
                  <Divider />
                  <Alert severity="warning">
                    A replacement receives a new identity and code. This historical asset remains{" "}
                    {asset.lifecycleState.toLowerCase()}.
                  </Alert>
                  <TextField
                    label="Replacement individual name"
                    value={replacementName}
                    onChange={(event) => setReplacementName(event.target.value)}
                  />
                  <Button variant="outlined" onClick={() => void replace()} disabled={busy}>
                    Create replacement
                  </Button>
                  {replacement ? (
                    <Link component={RouterLink} to={`/inventory/assets/${replacement.id}`}>
                      Replacement: {replacement.displayName} ({replacement.publicCode})
                    </Link>
                  ) : null}
                </>
              ) : null}
              {stateHistory.length ? (
                <>
                  <Divider />
                  <Typography variant="h4">Condition and lifecycle history</Typography>
                  {stateHistory.map((row) => (
                    <Typography key={row.id} variant="body2">
                      {row.changeType}: {row.previousValue}
                      {" \u2192 "}
                      {row.newValue} / {new Date(row.changedAt).toLocaleString()}
                      {row.reason ? ` / ${row.reason}` : ""}
                    </Typography>
                  ))}
                </>
              ) : null}
              {history.length ? (
                <>
                  <Divider />
                  <Typography variant="h4">Seal history</Typography>
                  {history.map((row, index) => (
                    <Typography key={index} variant="body2">
                      {row.action.replaceAll("_", " ")} /{" "}
                      {new Date(row.occurredAt).toLocaleString()}
                    </Typography>
                  ))}
                </>
              ) : null}
            </>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy}>
          {mode === "open" ? "Cancel" : "Done"}
        </Button>
        {mode === "open" ? (
          <Button
            variant="contained"
            onClick={() => void open()}
            disabled={busy || !reference.trim()}
          >
            Open repair
          </Button>
        ) : null}
      </DialogActions>
    </Dialog>
  );
}
