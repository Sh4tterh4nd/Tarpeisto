import { useCallback, useEffect, useState, type FormEvent } from "react";
import AddIcon from "@mui/icons-material/Add";
import ArrowBackIcon from "@mui/icons-material/ArrowBack";
import DeleteOutlineIcon from "@mui/icons-material/Delete";
import EditIcon from "@mui/icons-material/Edit";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { Link as RouterLink, useParams } from "react-router-dom";
import { PageHeading } from "@bigcontainers/shared-ui";
import { useSession } from "../identity/useSession";
import {
  listAssetModels,
  listAssets,
  listStockBalances,
  type AssetModelRecord,
  type AssetRecord,
  type StockBalanceRecord,
} from "../inventory/inventoryApi";
import { instantToLocalDateTime, localDateTimeToInstant } from "./eventDates";
import {
  addBookingLine,
  cancelBooking,
  eventErrorMessage,
  getBooking,
  getBookingHistory,
  getCheckoutManifest,
  previewBooking,
  removeBookingLine,
  reserveBooking,
  updateBooking,
  type BookingConflict,
  type BookingHistory,
  type BookingRecord,
  type BookingPreview,
  type CheckoutManifest,
} from "./eventsApi";
import { EventCheckoutPanel } from "./EventCheckoutPanel";

const MUTATING_ROLES = new Set(["OWNER", "DEPUTY"]);
type LineKind = "CONTAINER" | "ASSET" | "CONSUMABLE";

function BookingEditDialog({
  booking,
  onClose,
  onSaved,
}: {
  booking: BookingRecord;
  onClose: () => void;
  onSaved: (next: BookingRecord) => void;
}) {
  const [name, setName] = useState(booking.name);
  const [client, setClient] = useState(booking.clientText ?? "");
  const [venue, setVenue] = useState(booking.venueText ?? "");
  const [notes, setNotes] = useState(booking.notes ?? "");
  const [starts, setStarts] = useState(instantToLocalDateTime(booking.startsAt));
  const [ends, setEnds] = useState(instantToLocalDateTime(booking.endsAt));
  const [error, setError] = useState<string>();
  const [saving, setSaving] = useState(false);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (new Date(starts) >= new Date(ends)) {
      setError("Choose an end time after the start time.");
      return;
    }
    setSaving(true);
    const result = await updateBooking(booking.id, booking.version, {
      name: name.trim(),
      clientText: client.trim() || undefined,
      venueText: venue.trim() || undefined,
      notes: notes.trim() || undefined,
      startsAt: localDateTimeToInstant(starts),
      endsAt: localDateTimeToInstant(ends),
    });
    setSaving(false);
    if (result.kind === "error") {
      setError(eventErrorMessage(result.error));
      return;
    }
    onSaved(result.data);
  }
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={(event) => void submit(event)}>
        <DialogTitle>Edit draft</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {error ? <Alert severity="error">{error}</Alert> : null}
            <TextField
              required
              label="Event name"
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
            <TextField
              label="Client"
              value={client}
              onChange={(event) => setClient(event.target.value)}
            />
            <TextField
              label="Venue"
              value={venue}
              onChange={(event) => setVenue(event.target.value)}
            />
            <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
              <TextField
                required
                fullWidth
                label="Starts"
                type="datetime-local"
                slotProps={{ inputLabel: { shrink: true } }}
                value={starts}
                onChange={(event) => setStarts(event.target.value)}
              />
              <TextField
                required
                fullWidth
                label="Ends"
                type="datetime-local"
                slotProps={{ inputLabel: { shrink: true } }}
                value={ends}
                onChange={(event) => setEnds(event.target.value)}
              />
            </Stack>
            <TextField
              label="Notes"
              multiline
              minRows={3}
              value={notes}
              onChange={(event) => setNotes(event.target.value)}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={saving}>
            {saving ? "Saving…" : "Save draft"}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

function AddLineDialog({
  booking,
  onClose,
  onSaved,
}: {
  booking: BookingRecord;
  onClose: () => void;
  onSaved: (next: BookingRecord) => void;
}) {
  const [kind, setKind] = useState<LineKind>("CONTAINER");
  const [models, setModels] = useState<AssetModelRecord[]>([]);
  const [modelId, setModelId] = useState("");
  const [assets, setAssets] = useState<AssetRecord[]>([]);
  const [stock, setStock] = useState<StockBalanceRecord[]>([]);
  const [targetId, setTargetId] = useState("");
  const [quantity, setQuantity] = useState("1");
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    void (async () => {
      const result = await listAssetModels();
      if (result.kind === "error") {
        setError(eventErrorMessage(result.error));
        return;
      }
      setModels(result.data.filter((item) => !item.archived));
    })();
  }, []);
  useEffect(() => {
    if (!modelId) return;
    void (async () => {
      const result =
        kind === "CONSUMABLE" ? await listStockBalances(modelId) : await listAssets(modelId, false);
      if (result.kind === "error") {
        setError(eventErrorMessage(result.error));
        return;
      }
      if (kind === "CONSUMABLE") setStock(result.data as StockBalanceRecord[]);
      else setAssets(result.data as AssetRecord[]);
    })();
  }, [kind, modelId]);
  const visibleModels = models.filter((model) =>
    kind === "CONSUMABLE"
      ? model.trackingMode === "QUANTITY_STOCK"
      : model.trackingMode === "SERIALIZED_ASSET" &&
        (kind !== "CONTAINER" || model.canContainAssets),
  );
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!targetId || !Number.isFinite(Number(quantity)) || Number(quantity) <= 0) {
      setError("Choose an inventory item and a positive quantity.");
      return;
    }
    setBusy(true);
    const result = await addBookingLine(booking.id, {
      expectedBookingVersion: booking.version,
      type: kind,
      assetId: kind === "CONSUMABLE" ? undefined : targetId,
      consumableStockId: kind === "CONSUMABLE" ? targetId : undefined,
      quantity: Number(quantity),
    });
    setBusy(false);
    if (result.kind === "error") {
      setError(eventErrorMessage(result.error));
      return;
    }
    onSaved(result.data);
  }
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={(event) => void submit(event)}>
        <DialogTitle>Add reservation line</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {error ? <Alert severity="error">{error}</Alert> : null}
            <TextField
              select
              label="Reservation type"
              value={kind}
              onChange={(event) => {
                setKind(event.target.value as LineKind);
                setModelId("");
                setTargetId("");
                setAssets([]);
                setStock([]);
              }}
            >
              <MenuItem value="CONTAINER">Whole container</MenuItem>
              <MenuItem value="ASSET">Individual asset</MenuItem>
              <MenuItem value="CONSUMABLE">Consumable stock</MenuItem>
            </TextField>
            <TextField
              select
              required
              label="Model"
              value={modelId}
              onChange={(event) => {
                setModelId(event.target.value);
                setTargetId("");
                setAssets([]);
                setStock([]);
              }}
            >
              <MenuItem value="">Choose a model</MenuItem>
              {visibleModels.map((model) => (
                <MenuItem key={model.id} value={model.id}>
                  {model.name}
                </MenuItem>
              ))}
            </TextField>
            {kind === "CONSUMABLE" ? (
              <TextField
                select
                required
                label="Stock source"
                value={targetId}
                onChange={(event) => setTargetId(event.target.value)}
                disabled={!modelId}
              >
                <MenuItem value="">Choose a stock place</MenuItem>
                {stock.map((item) => (
                  <MenuItem key={item.id} value={item.id}>
                    {item.locationDisplayName ??
                      item.containerAssetDisplayName ??
                      "Unknown location"}{" "}
                    — {item.quantity} {item.stockUnitLabel}
                  </MenuItem>
                ))}
              </TextField>
            ) : (
              <TextField
                select
                required
                label={kind === "CONTAINER" ? "Container" : "Asset"}
                value={targetId}
                onChange={(event) => setTargetId(event.target.value)}
                disabled={!modelId}
              >
                <MenuItem value="">Choose an item</MenuItem>
                {assets.map((asset) => (
                  <MenuItem key={asset.id} value={asset.id}>
                    {asset.displayName} — {asset.publicCode}
                  </MenuItem>
                ))}
              </TextField>
            )}
            <TextField
              required
              label="Quantity"
              type="number"
              slotProps={{ htmlInput: { min: 0.001, step: 0.001 } }}
              value={quantity}
              onChange={(event) => setQuantity(event.target.value)}
              helperText={
                kind === "CONSUMABLE"
                  ? "Planned quantity from this stock place."
                  : "Serialized equipment is normally 1."
              }
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={busy}>
            {busy ? "Adding…" : "Add line"}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

function ConflictList({
  title,
  conflicts,
  severity,
}: {
  title: string;
  conflicts: BookingConflict[];
  severity: "error" | "warning";
}) {
  if (conflicts.length === 0) return null;
  return (
    <Alert severity={severity}>
      <Typography sx={{ fontWeight: 700 }}>{title}</Typography>
      <Stack component="ul" spacing={0.5} sx={{ my: 0, pl: 2.5 }}>
        {conflicts.map((conflict, index) => (
          <li key={`${conflict.type}-${index}`}>
            {conflict.message}
            {conflict.conflictingBookingId ? (
              <>
                {" "}
                <Button
                  component={RouterLink}
                  to={`/events/${conflict.conflictingBookingId}`}
                  size="small"
                >
                  View event
                </Button>
              </>
            ) : null}
          </li>
        ))}
      </Stack>
    </Alert>
  );
}

function lineName(line: BookingRecord["lines"][number]) {
  return line.type === "CONSUMABLE"
    ? `Consumable stock ${line.consumableStockId}`
    : `${line.type === "CONTAINER" ? "Container" : "Asset"} ${line.assetId}`;
}

export function EventDetailPage() {
  const { bookingId = "" } = useParams();
  const { role } = useSession();
  const canMutate = MUTATING_ROLES.has(role ?? "");
  const [booking, setBooking] = useState<BookingRecord>();
  const [history, setHistory] = useState<BookingHistory[]>([]);
  const [preview, setPreview] = useState<BookingPreview>();
  const [manifest, setManifest] = useState<CheckoutManifest>();
  const [error, setError] = useState<string>();
  const [editing, setEditing] = useState(false);
  const [adding, setAdding] = useState(false);
  const [busy, setBusy] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const load = useCallback(async () => {
    setError(undefined);
    const [detail, historyResult] = await Promise.all([
      getBooking(bookingId),
      getBookingHistory(bookingId),
    ]);
    if (detail.kind === "error") {
      setError(eventErrorMessage(detail.error));
      return;
    }
    setBooking(detail.data);
    if (historyResult.kind === "ok") setHistory(historyResult.data);
    if (
      ["CHECKED_OUT", "RETURNED_AUDITS_PENDING", "REVIEW_REQUIRED", "COMPLETED"].includes(
        detail.data.status,
      )
    ) {
      const manifestResult = await getCheckoutManifest(bookingId);
      if (manifestResult.kind === "ok") setManifest(manifestResult.data);
      else setError(eventErrorMessage(manifestResult.error));
    } else {
      setManifest(undefined);
    }
  }, [bookingId]);
  useEffect(() => {
    const timer = window.setTimeout(() => void load());
    return () => window.clearTimeout(timer);
  }, [load]);
  const runPreview = async () => {
    const result = await previewBooking(bookingId);
    if (result.kind === "error") {
      setError(eventErrorMessage(result.error));
      return;
    }
    setPreview(result.data);
  };
  const reserve = async () => {
    if (!booking) return;
    setBusy(true);
    const result = await reserveBooking(booking.id, booking.version);
    setBusy(false);
    if (result.kind === "error") {
      setError(eventErrorMessage(result.error));
      await load();
      return;
    }
    setPreview(result.data);
    await load();
  };
  const remove = async (lineId: string) => {
    if (!booking) return;
    setBusy(true);
    const result = await removeBookingLine(booking.id, lineId, booking.version);
    setBusy(false);
    if (result.kind === "error") {
      setError(eventErrorMessage(result.error));
      await load();
      return;
    }
    setBooking(result.data);
    void getBookingHistory(bookingId).then(
      (result) => result.kind === "ok" && setHistory(result.data),
    );
  };
  const cancel = async () => {
    if (!booking) return;
    setBusy(true);
    const result = await cancelBooking(booking.id, booking.version);
    setBusy(false);
    setConfirmCancel(false);
    if (result.kind === "error") {
      setError(eventErrorMessage(result.error));
      await load();
      return;
    }
    setBooking(result.data);
  };
  if (!booking && !error) return <CircularProgress />;
  if (!booking)
    return (
      <Stack spacing={2}>
        <Button component={RouterLink} to="/events" startIcon={<ArrowBackIcon />}>
          Back to events
        </Button>
        <Alert severity="error">{error}</Alert>
      </Stack>
    );
  const draft = booking.status === "DRAFT";
  return (
    <Stack spacing={2}>
      <Button
        component={RouterLink}
        to="/events"
        startIcon={<ArrowBackIcon />}
        sx={{ alignSelf: "start" }}
      >
        All events
      </Button>
      <Stack
        direction={{ xs: "column", md: "row" }}
        spacing={2}
        sx={{ justifyContent: "space-between" }}
      >
        <PageHeading
          title={booking.name}
          description={`${new Intl.DateTimeFormat(undefined, { dateStyle: "full", timeStyle: "short" }).format(new Date(booking.startsAt))} — ${new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(booking.endsAt))}`}
        />
        <Stack direction="row" spacing={1} sx={{ alignItems: "start" }}>
          <Chip
            label={booking.status.replaceAll("_", " ")}
            color={booking.status === "RESERVED" ? "success" : "default"}
          />
          {booking.reservationStatus === "ATTENTION_REQUIRED" ? (
            <Chip label="Attention required" color="warning" />
          ) : null}
        </Stack>
      </Stack>
      {error ? <Alert severity="error">{error}</Alert> : null}
      {booking.reservationStatus === "ATTENTION_REQUIRED" ? (
        <Alert severity="warning">
          This reservation needs attention because inventory or packing changed. Preview it before
          any next step.
        </Alert>
      ) : null}
      <Paper sx={{ p: 2 }}>
        <Stack spacing={1}>
          <Typography variant="h6">Event details</Typography>
          <Typography>
            <b>Client:</b> {booking.clientText || "—"}
          </Typography>
          <Typography>
            <b>Venue:</b> {booking.venueText || "—"}
          </Typography>
          <Typography>
            <b>Notes:</b> {booking.notes || "—"}
          </Typography>
          {canMutate && draft ? (
            <Button
              startIcon={<EditIcon />}
              onClick={() => setEditing(true)}
              sx={{ alignSelf: "start" }}
            >
              Edit draft
            </Button>
          ) : null}
        </Stack>
      </Paper>
      <EventCheckoutPanel
        booking={booking}
        role={role}
        manifest={manifest}
        onManifest={(next) => {
          setManifest(next);
          void load();
        }}
      />
      <Paper sx={{ p: 2 }}>
        <Stack spacing={1.5}>
          <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "center" }}>
            <Typography variant="h6">Equipment plan</Typography>
            {canMutate && draft ? (
              <Button startIcon={<AddIcon />} onClick={() => setAdding(true)}>
                Add equipment
              </Button>
            ) : null}
          </Stack>
          {booking.lines.length === 0 ? (
            <Typography color="text.secondary">No equipment has been planned yet.</Typography>
          ) : (
            booking.lines.map((line) => (
              <Stack
                key={line.id}
                direction="row"
                sx={{ justifyContent: "space-between", alignItems: "center" }}
              >
                <Typography>
                  {lineName(line)} — quantity {line.quantity}
                </Typography>
                {canMutate && draft ? (
                  <Button
                    color="error"
                    size="small"
                    startIcon={<DeleteOutlineIcon />}
                    disabled={busy}
                    onClick={() => void remove(line.id)}
                  >
                    Remove
                  </Button>
                ) : null}
              </Stack>
            ))
          )}
        </Stack>
      </Paper>
      <Paper sx={{ p: 2 }}>
        <Stack direction={{ xs: "column", sm: "row" }} spacing={1}>
          <Button variant="outlined" onClick={() => void runPreview()}>
            Preview availability
          </Button>
          {canMutate && draft ? (
            <Button variant="contained" disabled={busy} onClick={() => void reserve()}>
              Reserve equipment
            </Button>
          ) : null}
          {canMutate && (draft || booking.status === "RESERVED") ? (
            <Button color="error" disabled={busy} onClick={() => setConfirmCancel(true)}>
              Cancel event
            </Button>
          ) : null}
        </Stack>
      </Paper>
      {preview ? (
        <Stack spacing={1}>
          <ConflictList
            title="Reservation blockers"
            conflicts={preview.conflicts}
            severity="error"
          />
          <ConflictList title="Warnings" conflicts={preview.warnings} severity="warning" />
          {preview.reservable ? <Alert severity="success">This plan can be reserved.</Alert> : null}
        </Stack>
      ) : null}
      <Paper sx={{ p: 2 }}>
        <Typography variant="h6" gutterBottom>
          Activity
        </Typography>
        {history.length === 0 ? (
          <Typography color="text.secondary">No activity recorded yet.</Typography>
        ) : (
          <Stack spacing={1}>
            {history.map((entry) => (
              <Stack key={entry.id} direction="row" sx={{ justifyContent: "space-between" }}>
                <Typography>{entry.action.replaceAll("_", " ")}</Typography>
                <Typography variant="body2" color="text.secondary">
                  {new Intl.DateTimeFormat(undefined, {
                    dateStyle: "medium",
                    timeStyle: "short",
                  }).format(new Date(entry.occurredAt))}
                </Typography>
              </Stack>
            ))}
          </Stack>
        )}
      </Paper>
      {editing ? (
        <BookingEditDialog
          booking={booking}
          onClose={() => setEditing(false)}
          onSaved={(next) => {
            setBooking(next);
            setEditing(false);
          }}
        />
      ) : null}
      {adding ? (
        <AddLineDialog
          booking={booking}
          onClose={() => setAdding(false)}
          onSaved={(next) => {
            setBooking(next);
            setAdding(false);
          }}
        />
      ) : null}
      <Dialog open={confirmCancel} onClose={() => setConfirmCancel(false)}>
        <DialogTitle>Cancel this event?</DialogTitle>
        <DialogContent>
          Reservations will be released. This does not delete the event history.
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmCancel(false)}>Keep event</Button>
          <Button color="error" variant="contained" onClick={() => void cancel()}>
            Cancel event
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
