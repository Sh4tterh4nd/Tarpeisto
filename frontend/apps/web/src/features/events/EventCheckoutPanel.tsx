import { useEffect, useState, type FormEvent } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Divider from "@mui/material/Divider";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { Link as RouterLink } from "react-router-dom";
import { validateAssetCode } from "../asset-code/normalizeAssetCode";
import {
  listAssetModels,
  listAssets,
  listLocations,
  type AssetRecord,
  type LocationRecord,
} from "../inventory/inventoryApi";
import { lookupScannedAsset } from "../scanner/scannerApi";
import {
  checkInBookingAsset,
  checkoutBooking,
  completeBookingReturn,
  eventErrorMessage,
  returnBookingConsumable,
  type BookingRecord,
  type CheckoutManifest,
  type CheckoutManifestConsumable,
} from "./eventsApi";

const OPERATION_ROLES = new Set(["OWNER", "DEPUTY", "OPERATOR_AUDITOR"]);

function snapshotText(snapshot: Record<string, unknown>, key: string, fallback = "Unnamed item") {
  const value = snapshot[key];
  return typeof value === "string" || typeof value === "number" ? String(value) : fallback;
}

function assetName(item: CheckoutManifest["assets"][number]) {
  return `${snapshotText(item.snapshot, "modelName")} ${snapshotText(item.snapshot, "individualName", "")}`.trim();
}

function manifestPdfUrl(bookingId: string) {
  return `/api/v1/bookings/${encodeURIComponent(bookingId)}/checkout-manifest.pdf`;
}

function CheckoutDialog({
  booking,
  role,
  onClose,
  onCheckedOut,
}: {
  booking: BookingRecord;
  role: string | undefined;
  onClose: () => void;
  onCheckedOut: (manifest: CheckoutManifest) => void;
}) {
  const [candidates, setCandidates] = useState<AssetRecord[]>([]);
  const [selectedAssetIds, setSelectedAssetIds] = useState<string[]>([]);
  const [overrideReason, setOverrideReason] = useState("");
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void (async () => {
      const models = await listAssetModels();
      if (models.kind === "error") {
        setError(eventErrorMessage(models.error));
        return;
      }
      const serialized = models.data.filter(
        (model) => !model.archived && model.trackingMode === "SERIALIZED_ASSET",
      );
      const results = await Promise.all(serialized.map((model) => listAssets(model.id, false)));
      setCandidates(results.flatMap((result) => (result.kind === "ok" ? result.data : [])));
    })();
  }, []);

  function toggle(assetId: string) {
    setSelectedAssetIds((current) =>
      current.includes(assetId) ? current.filter((id) => id !== assetId) : [...current, assetId],
    );
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    const result = await checkoutBooking(booking.id, {
      expectedVersion: booking.version,
      mutationId: crypto.randomUUID(),
      overrideReason: overrideReason.trim() || undefined,
      selectedAssetIds,
    });
    setBusy(false);
    if (result.kind === "error") {
      setError(eventErrorMessage(result.error));
      return;
    }
    onCheckedOut(result.data);
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <Stack component="form" spacing={2} onSubmit={(event) => void submit(event)} sx={{ p: 3 }}>
        <DialogTitle sx={{ p: 0 }}>Check out event</DialogTitle>
        <DialogContent sx={{ p: 0 }}>
          <Stack spacing={2}>
            <Typography color="text.secondary" sx={{ maxWidth: 640 }}>
              This freezes the exact manifest. Packing changes after checkout cannot alter what left
              the warehouse.
            </Typography>
            {error ? <Alert severity="error">{error}</Alert> : null}
            <Divider />
            <Typography variant="h6">Select replacements when needed</Typography>
            <Typography variant="body2" color="text.secondary">
              If the reservation has interchangeable equipment, select the exact units leaving now.
              The server checks that every choice is eligible and available.
            </Typography>
            {candidates.length === 0 ? (
              <Typography color="text.secondary">
                Loading available serialized equipment.
              </Typography>
            ) : (
              <Stack sx={{ maxHeight: 240, overflowY: "auto" }}>
                {candidates.map((asset) => (
                  <FormControlLabel
                    key={asset.id}
                    control={
                      <Checkbox
                        checked={selectedAssetIds.includes(asset.id)}
                        onChange={() => toggle(asset.id)}
                      />
                    }
                    label={`${asset.displayName} — ${asset.publicCode}`}
                  />
                ))}
              </Stack>
            )}
            {role === "OWNER" || role === "DEPUTY" ? (
              <TextField
                label="Override reason"
                value={overrideReason}
                onChange={(event) => setOverrideReason(event.target.value)}
                helperText="Required only when checking out despite a reservation blocker."
                multiline
                minRows={2}
              />
            ) : null}
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 0, pb: 0 }}>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={busy}>
            {busy ? "Freezing manifest" : "Check out event"}
          </Button>
        </DialogActions>
      </Stack>
    </Dialog>
  );
}

function ReturnConsumableDialog({
  bookingId,
  line,
  onClose,
  onReturned,
}: {
  bookingId: string;
  line: CheckoutManifestConsumable;
  onClose: () => void;
  onReturned: (manifest: CheckoutManifest) => void;
}) {
  const [quantity, setQuantity] = useState("");
  const [locations, setLocations] = useState<LocationRecord[]>([]);
  const [destinationLocationId, setDestinationLocationId] = useState("");
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const remaining = line.quantity - line.returnedQuantity;
  useEffect(() => {
    void listLocations().then((result) => {
      if (result.kind === "ok") setLocations(result.data.filter((location) => !location.archived));
      else setError(eventErrorMessage(result.error));
    });
  }, []);
  async function submit(event: FormEvent) {
    event.preventDefault();
    const amount = Number(quantity);
    if (!Number.isFinite(amount) || amount <= 0 || amount > remaining || !destinationLocationId) {
      setError("Enter an amount up to the outstanding quantity and choose where it returns.");
      return;
    }
    setBusy(true);
    const result = await returnBookingConsumable(bookingId, line.id, {
      mutationId: crypto.randomUUID(),
      quantity: amount,
      destinationLocationId,
    });
    setBusy(false);
    if (result.kind === "error") {
      setError(eventErrorMessage(result.error));
      return;
    }
    onReturned(result.data);
  }
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <Stack component="form" spacing={2} onSubmit={(event) => void submit(event)} sx={{ p: 3 }}>
        <DialogTitle sx={{ p: 0 }}>Record consumable return</DialogTitle>
        <DialogContent sx={{ p: 0 }}>
          <Stack spacing={2}>
            <Typography color="text.secondary">
              {snapshotText(line.snapshot, "modelName")} has {remaining} remaining to account for.
            </Typography>
            {error ? <Alert severity="error">{error}</Alert> : null}
            <TextField
              required
              type="number"
              label="Returned quantity"
              value={quantity}
              onChange={(event) => setQuantity(event.target.value)}
              slotProps={{ htmlInput: { min: 0.001, max: remaining, step: 0.001 } }}
            />
            <TextField
              select
              required
              label="Return to location"
              value={destinationLocationId}
              onChange={(event) => setDestinationLocationId(event.target.value)}
            >
              <MenuItem value="">Choose a location</MenuItem>
              {locations.map((location) => (
                <MenuItem key={location.id} value={location.id}>
                  {location.effectivePath}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 0, pb: 0 }}>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={busy}>
            {busy ? "Recording" : "Record return"}
          </Button>
        </DialogActions>
      </Stack>
    </Dialog>
  );
}

export function EventCheckoutPanel({
  booking,
  role,
  manifest,
  onManifest,
}: {
  booking: BookingRecord;
  role: string | undefined;
  manifest: CheckoutManifest | undefined;
  onManifest: (next: CheckoutManifest) => void;
}) {
  const [checkoutOpen, setCheckoutOpen] = useState(false);
  const [consumable, setConsumable] = useState<CheckoutManifestConsumable>();
  const [manualCode, setManualCode] = useState("");
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const canOperate = OPERATION_ROLES.has(role ?? "");
  const returnedAssets = manifest?.assets.filter((asset) => asset.returnedAt).length ?? 0;
  const totalAssets = manifest?.assets.length ?? 0;
  const outstandingAssets = totalAssets - returnedAssets;
  const auditReady = manifest?.auditTasks.filter((task) => task.state === "READY").length ?? 0;
  const auditBlocked = manifest?.auditTasks.filter((task) => task.state === "BLOCKED").length ?? 0;

  async function returnAsset(assetId: string) {
    setBusy(true);
    const result = await checkInBookingAsset(booking.id, assetId, crypto.randomUUID());
    setBusy(false);
    if (result.kind === "error") setError(eventErrorMessage(result.error));
    else onManifest(result.data);
  }

  async function returnManual(event: FormEvent) {
    event.preventDefault();
    const validation = validateAssetCode(manualCode);
    if (!validation.valid) {
      setError("Enter the six-character public code from the equipment label.");
      return;
    }
    const asset = await lookupScannedAsset(validation.normalized);
    if (asset.kind === "error") {
      setError(eventErrorMessage(asset.error));
      return;
    }
    if (!manifest?.assets.some((item) => item.assetId === asset.data.id && !item.returnedAt)) {
      setError("That asset is not outstanding on this event manifest.");
      return;
    }
    await returnAsset(asset.data.id);
    setManualCode("");
  }

  async function completeReturn() {
    setBusy(true);
    const result = await completeBookingReturn(booking.id, crypto.randomUUID());
    setBusy(false);
    if (result.kind === "error") setError(eventErrorMessage(result.error));
    else onManifest(result.data);
  }

  if (booking.status === "RESERVED") {
    return (
      <Paper component="section" sx={{ p: 2.5, borderLeft: 5, borderColor: "secondary.main" }}>
        <Stack spacing={1.5}>
          <Typography variant="h6">Ready to leave the warehouse</Typography>
          <Typography color="text.secondary" sx={{ maxWidth: 700 }}>
            Confirm the exact equipment and freeze the checkout manifest before custody changes.
          </Typography>
          {canOperate ? (
            <Button
              variant="contained"
              onClick={() => setCheckoutOpen(true)}
              sx={{ alignSelf: "start", minHeight: 44 }}
            >
              Check out event
            </Button>
          ) : null}
        </Stack>
        {checkoutOpen ? (
          <CheckoutDialog
            booking={booking}
            role={role}
            onClose={() => setCheckoutOpen(false)}
            onCheckedOut={(next) => {
              onManifest(next);
              setCheckoutOpen(false);
            }}
          />
        ) : null}
      </Paper>
    );
  }
  if (!manifest) return null;
  return (
    <Stack spacing={2} component="section" aria-labelledby="checkout-heading">
      <Paper
        sx={{
          p: 2.5,
          bgcolor: "primary.main",
          color: "primary.contrastText",
          borderRadius: 1,
        }}
      >
        <Stack
          direction={{ xs: "column", md: "row" }}
          spacing={2}
          sx={{ justifyContent: "space-between" }}
        >
          <Stack spacing={0.5}>
            <Typography id="checkout-heading" variant="h6">
              Return progress
            </Typography>
            <Typography variant="h3" sx={{ fontWeight: 800 }}>
              {returnedAssets} / {totalAssets} assets returned
            </Typography>
            <Typography>
              {outstandingAssets === 0
                ? "All equipment is back; finish return accounting."
                : `${outstandingAssets} asset${outstandingAssets === 1 ? "" : "s"} still out.`}
            </Typography>
          </Stack>
          <Stack spacing={1} sx={{ alignItems: { md: "flex-end" } }}>
            <Button
              component="a"
              href={manifestPdfUrl(booking.id)}
              target="_blank"
              rel="noreferrer"
              color="inherit"
              variant="outlined"
            >
              Download manifest
            </Button>
            <Typography variant="body2">
              Frozen at{" "}
              {new Intl.DateTimeFormat(undefined, {
                dateStyle: "medium",
                timeStyle: "short",
              }).format(new Date(manifest.checkedOutAt))}
            </Typography>
          </Stack>
        </Stack>
      </Paper>

      {error ? <Alert severity="error">{error}</Alert> : null}
      <Paper
        component="form"
        onSubmit={(event) => void returnManual(event)}
        sx={{ p: 2, borderLeft: 5, borderColor: "secondary.main" }}
      >
        <Stack
          direction={{ xs: "column", sm: "row" }}
          spacing={1.5}
          sx={{ alignItems: { sm: "center" } }}
        >
          <Stack sx={{ flex: 1 }}>
            <Typography variant="h6">Record an equipment return</Typography>
            <Typography color="text.secondary">
              Scan in the scanner or enter the label code here.
            </Typography>
          </Stack>
          <TextField
            label="Public asset code"
            value={manualCode}
            onChange={(event) => setManualCode(event.target.value)}
            slotProps={{ htmlInput: { inputMode: "text" } }}
          />
          <Button
            type="submit"
            variant="contained"
            disabled={!canOperate || busy}
            sx={{ minHeight: 44 }}
          >
            Record return
          </Button>
        </Stack>
      </Paper>

      <Stack spacing={1}>
        <Typography variant="h6">Frozen equipment manifest</Typography>
        {manifest.assets.map((asset) => (
          <Stack
            key={asset.assetId}
            direction={{ xs: "column", sm: "row" }}
            spacing={1}
            sx={{
              py: 1,
              borderBottom: 1,
              borderColor: "divider",
              justifyContent: "space-between",
              alignItems: { sm: "center" },
            }}
          >
            <Stack>
              <Typography sx={{ fontWeight: 700 }}>{assetName(asset)}</Typography>
              <Typography variant="body2" color="text.secondary">
                {snapshotText(asset.snapshot, "assetCode", asset.assetId)}
              </Typography>
            </Stack>
            {asset.returnedAt ? (
              <Typography color="success.main">Returned</Typography>
            ) : (
              <Button
                onClick={() => void returnAsset(asset.assetId)}
                disabled={!canOperate || busy}
              >
                Record return
              </Button>
            )}
          </Stack>
        ))}
      </Stack>

      <Stack spacing={1}>
        <Typography variant="h6">Consumables</Typography>
        {manifest.consumables.length === 0 ? (
          <Typography color="text.secondary">No consumables were issued for this event.</Typography>
        ) : (
          manifest.consumables.map((line) => {
            const remaining = line.quantity - line.returnedQuantity;
            return (
              <Stack
                key={line.id}
                direction={{ xs: "column", sm: "row" }}
                spacing={1}
                sx={{
                  py: 1,
                  borderBottom: 1,
                  borderColor: "divider",
                  justifyContent: "space-between",
                  alignItems: { sm: "center" },
                }}
              >
                <Typography>
                  {snapshotText(line.snapshot, "modelName")} — {line.quantity} issued,{" "}
                  {line.returnedQuantity} returned
                </Typography>
                {line.semantics === "CARRIED_IN_CONTAINER" ? (
                  <Typography color="text.secondary">Carried in container</Typography>
                ) : remaining > 0 ? (
                  <Button onClick={() => setConsumable(line)} disabled={!canOperate}>
                    Record consumable return
                  </Button>
                ) : (
                  <Typography color="success.main">Fully returned</Typography>
                )}
              </Stack>
            );
          })
        )}
      </Stack>

      <Paper sx={{ p: 2 }}>
        <Typography variant="h6">Audit tasks</Typography>
        <Typography color="text.secondary">
          {auditReady} ready; {auditBlocked} blocked until their child-container audits are
          complete.
        </Typography>
        {manifest.auditTasks.length === 0 ? (
          <Typography color="text.secondary" sx={{ mt: 1 }}>
            Audit tasks appear when returned containers need verification.
          </Typography>
        ) : (
          <Stack component="ul" sx={{ pl: 2.5, mb: 0 }}>
            {manifest.auditTasks.map((task) => (
              <Stack
                component="li"
                key={task.id}
                direction={{ xs: "column", sm: "row" }}
                spacing={1}
                sx={{ alignItems: { sm: "center" } }}
                color={
                  task.state === "READY"
                    ? "success.main"
                    : task.state === "BLOCKED"
                      ? "warning.main"
                      : "text.secondary"
                }
              >
                <Typography>
                  {task.state === "READY"
                    ? "Ready to audit"
                    : task.state === "BLOCKED"
                      ? `Blocked — complete ${task.dependsOnTaskIds.length} contained-case audit${task.dependsOnTaskIds.length === 1 ? "" : "s"} first`
                      : "Completed"}
                  : {task.containerAssetId}
                </Typography>
                {task.state === "READY" ? (
                  <Button
                    component={RouterLink}
                    to={`/audits/tasks/${task.id}`}
                    size="small"
                    variant="outlined"
                  >
                    Open audit
                  </Button>
                ) : null}
              </Stack>
            ))}
          </Stack>
        )}
      </Paper>

      {canOperate && outstandingAssets === 0 ? (
        <Button
          variant="contained"
          onClick={() => void completeReturn()}
          disabled={busy}
          sx={{ alignSelf: "start", minHeight: 44 }}
        >
          Finish return and account for unused consumables
        </Button>
      ) : null}
      {consumable ? (
        <ReturnConsumableDialog
          bookingId={booking.id}
          line={consumable}
          onClose={() => setConsumable(undefined)}
          onReturned={(next) => {
            onManifest(next);
            setConsumable(undefined);
          }}
        />
      ) : null}
      <Button component={RouterLink} to="/scan" variant="text" sx={{ alignSelf: "start" }}>
        Open camera scanner
      </Button>
    </Stack>
  );
}
