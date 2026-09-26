import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import AddIcon from "@mui/icons-material/Add";
import ArrowBackIcon from "@mui/icons-material/ArrowBack";
import ArrowForwardIcon from "@mui/icons-material/ArrowForward";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";
import Typography from "@mui/material/Typography";
import { Link as RouterLink, useNavigate } from "react-router-dom";
import { PageHeading } from "@bigcontainers/shared-ui";
import { useSession } from "../identity/useSession";
import { eventOverlapsDay, localDateTimeToInstant, monthBounds } from "./eventDates";
import {
  createBooking,
  eventErrorMessage,
  listBookings,
  type BookingInput,
  type BookingRecord,
} from "./eventsApi";

const MUTATING_ROLES = new Set(["OWNER", "DEPUTY"]);
const statusColor = { DRAFT: "default", RESERVED: "success", CANCELLED: "default" } as const;

function statusChip(
  status: BookingRecord["status"],
  reservationStatus: BookingRecord["reservationStatus"],
) {
  return (
    <Stack direction="row" spacing={0.5}>
      <Chip
        size="small"
        label={status.replaceAll("_", " ")}
        color={statusColor[status as keyof typeof statusColor] ?? "warning"}
      />
      {reservationStatus === "ATTENTION_REQUIRED" ? (
        <Chip size="small" color="warning" label="Attention required" />
      ) : null}
    </Stack>
  );
}

function NewEventDialog({
  onClose,
  onCreated,
}: {
  onClose: () => void;
  onCreated: (booking: BookingRecord) => void;
}) {
  const [name, setName] = useState("");
  const [clientText, setClientText] = useState("");
  const [venueText, setVenueText] = useState("");
  const [notes, setNotes] = useState("");
  const [startsAt, setStartsAt] = useState("");
  const [endsAt, setEndsAt] = useState("");
  const [error, setError] = useState<string>();
  const [saving, setSaving] = useState(false);
  const [mutationId] = useState(() => crypto.randomUUID());

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!startsAt || !endsAt || new Date(startsAt) >= new Date(endsAt)) {
      setError("Choose an end time after the start time.");
      return;
    }
    setSaving(true);
    setError(undefined);
    const input: BookingInput = {
      mutationId,
      name: name.trim(),
      clientText: clientText.trim() || undefined,
      venueText: venueText.trim() || undefined,
      notes: notes.trim() || undefined,
      startsAt: localDateTimeToInstant(startsAt),
      endsAt: localDateTimeToInstant(endsAt),
    };
    const result = await createBooking(input);
    setSaving(false);
    if (result.kind === "error") {
      setError(eventErrorMessage(result.error));
      return;
    }
    onCreated(result.data);
  }
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={(event) => void submit(event)}>
        <DialogTitle>Plan an event</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {error ? <Alert severity="error">{error}</Alert> : null}
            <TextField
              required
              autoFocus
              label="Event name"
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
            <TextField
              label="Client"
              value={clientText}
              onChange={(event) => setClientText(event.target.value)}
            />
            <TextField
              label="Venue"
              value={venueText}
              onChange={(event) => setVenueText(event.target.value)}
            />
            <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
              <TextField
                required
                label="Starts"
                type="datetime-local"
                slotProps={{ inputLabel: { shrink: true } }}
                value={startsAt}
                onChange={(event) => setStartsAt(event.target.value)}
                fullWidth
              />
              <TextField
                required
                label="Ends"
                type="datetime-local"
                slotProps={{ inputLabel: { shrink: true } }}
                value={endsAt}
                onChange={(event) => setEndsAt(event.target.value)}
                fullWidth
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
            {saving ? "Creating…" : "Create draft"}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

export function EventsPage() {
  const { role } = useSession();
  const navigate = useNavigate();
  const [month, setMonth] = useState(() => new Date());
  const [bookings, setBookings] = useState<BookingRecord[]>();
  const [error, setError] = useState<string>();
  const [view, setView] = useState<"calendar" | "list">("calendar");
  const [creating, setCreating] = useState(false);
  const canMutate = MUTATING_ROLES.has(role ?? "");
  const load = useCallback(async () => {
    setError(undefined);
    const bounds = monthBounds(month);
    const result = await listBookings(bounds.from, bounds.until);
    if (result.kind === "error") {
      setError(eventErrorMessage(result.error));
      return;
    }
    setBookings(result.data);
  }, [month]);
  useEffect(() => {
    const timer = window.setTimeout(() => void load());
    return () => window.clearTimeout(timer);
  }, [load]);
  const days = useMemo(() => {
    const start = new Date(month.getFullYear(), month.getMonth(), 1);
    const weekday = (start.getDay() + 6) % 7;
    const first = new Date(month.getFullYear(), month.getMonth(), 1 - weekday);
    return Array.from(
      { length: 42 },
      (_, index) => new Date(first.getFullYear(), first.getMonth(), first.getDate() + index),
    );
  }, [month]);
  const monthTitle = new Intl.DateTimeFormat(undefined, { month: "long", year: "numeric" }).format(
    month,
  );
  return (
    <Stack spacing={2}>
      <Stack
        direction={{ xs: "column", sm: "row" }}
        spacing={1}
        sx={{ justifyContent: "space-between", alignItems: { sm: "center" } }}
      >
        <PageHeading
          title="Events"
          description="Plan equipment reservations before the warehouse is packed."
        />
        {canMutate ? (
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => setCreating(true)}>
            New event
          </Button>
        ) : null}
      </Stack>
      {error ? <Alert severity="error">{error}</Alert> : null}
      <Paper sx={{ p: 1.5 }}>
        <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "center" }}>
          <Button
            aria-label="Previous month"
            startIcon={<ArrowBackIcon />}
            onClick={() =>
              setMonth((value) => new Date(value.getFullYear(), value.getMonth() - 1, 1))
            }
          >
            Previous
          </Button>
          <Typography variant="h6">{monthTitle}</Typography>
          <Button
            aria-label="Next month"
            endIcon={<ArrowForwardIcon />}
            onClick={() =>
              setMonth((value) => new Date(value.getFullYear(), value.getMonth() + 1, 1))
            }
          >
            Next
          </Button>
        </Stack>
      </Paper>
      <ToggleButtonGroup
        exclusive
        value={view}
        onChange={(_, value: "calendar" | "list" | null) => value && setView(value)}
        size="small"
      >
        <ToggleButton value="calendar">Calendar</ToggleButton>
        <ToggleButton value="list">List</ToggleButton>
      </ToggleButtonGroup>
      {!bookings ? (
        <CircularProgress />
      ) : view === "list" ? (
        <Stack spacing={1}>
          {bookings.length === 0 ? (
            <Alert severity="info">No events in this period.</Alert>
          ) : (
            bookings.map((booking) => (
              <Paper
                key={booking.id}
                component={RouterLink}
                to={`/events/${booking.id}`}
                sx={{ p: 2, color: "inherit", textDecoration: "none" }}
              >
                <Stack direction="row" spacing={2} sx={{ justifyContent: "space-between" }}>
                  <Box>
                    <Typography sx={{ fontWeight: 700 }}>{booking.name}</Typography>
                    <Typography variant="body2">
                      {new Intl.DateTimeFormat(undefined, {
                        dateStyle: "medium",
                        timeStyle: "short",
                      }).format(new Date(booking.startsAt))}{" "}
                      — {booking.venueText || "No venue"}
                    </Typography>
                  </Box>
                  {statusChip(booking.status, booking.reservationStatus)}
                </Stack>
              </Paper>
            ))
          )}
        </Stack>
      ) : (
        <Box sx={{ display: "grid", gridTemplateColumns: "repeat(7, minmax(0, 1fr))", gap: 0.5 }}>
          {["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].map((label) => (
            <Typography key={label} variant="caption" sx={{ textAlign: "center" }}>
              {label}
            </Typography>
          ))}
          {days.map((day) => {
            const inMonth = day.getMonth() === month.getMonth();
            const dayEvents = bookings.filter((booking) =>
              eventOverlapsDay(booking.startsAt, booking.endsAt, day),
            );
            return (
              <Paper
                key={day.toISOString()}
                variant="outlined"
                sx={{ p: 0.75, minHeight: 120, opacity: inMonth ? 1 : 0.45 }}
              >
                <Typography variant="caption" sx={{ fontWeight: 700 }}>
                  {day.getDate()}
                </Typography>
                <Stack spacing={0.5} sx={{ mt: 0.5 }}>
                  {dayEvents.slice(0, 3).map((booking) => (
                    <Button
                      key={booking.id}
                      component={RouterLink}
                      to={`/events/${booking.id}`}
                      size="small"
                      sx={{
                        justifyContent: "flex-start",
                        textTransform: "none",
                        overflow: "hidden",
                        whiteSpace: "nowrap",
                      }}
                    >
                      {booking.name}
                    </Button>
                  ))}
                  {dayEvents.length > 3 ? (
                    <Typography variant="caption">+{dayEvents.length - 3} more</Typography>
                  ) : null}
                </Stack>
              </Paper>
            );
          })}
        </Box>
      )}
      {creating ? (
        <NewEventDialog
          onClose={() => setCreating(false)}
          onCreated={(booking) => navigate(`/events/${booking.id}`)}
        />
      ) : null}
    </Stack>
  );
}
