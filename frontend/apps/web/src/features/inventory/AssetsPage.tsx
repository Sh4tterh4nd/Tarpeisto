import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TableSortLabel from "@mui/material/TableSortLabel";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { Link as RouterLink, useSearchParams } from "react-router-dom";
import { listBookings, type BookingRecord } from "../events/eventsApi";
import { PageHeading } from "@tarpeisto/shared-ui";
import {
  errorMessage,
  listCategories,
  listLocations,
  type LocationRecord,
  searchAssets,
  type AssetSearchRecord,
  type CategoryRecord,
} from "./inventoryApi";

type SortKey = "name" | "code" | "condition" | "lifecycle";

export interface AssetsPageProps {
  readonly containersOnly?: boolean;
}

export function AssetsPage({ containersOnly = false }: AssetsPageProps) {
  const [assets, setAssets] = useState<AssetSearchRecord[]>([]);
  const [categories, setCategories] = useState<CategoryRecord[]>([]);
  const [nextCursor, setNextCursor] = useState<string>();
  const [searchParams] = useSearchParams();
  const [query, setQuery] = useState(() => searchParams.get("query") ?? "");
  const [category, setCategory] = useState("");
  const [containerOnly, setContainerOnly] = useState<string>(containersOnly ? "containers" : "");
  const [includeInactive, setIncludeInactive] = useState(false);
  const [includeArchived, setIncludeArchived] = useState(false);
  const [locations, setLocations] = useState<LocationRecord[]>([]);
  const [location, setLocation] = useState("");
  const [condition, setCondition] = useState<"" | "GOOD" | "DAMAGED">("");
  const [lifecycle, setLifecycle] = useState<"" | "ACTIVE" | "RETIRED" | "LOST" | "DESTROYED">("");
  const [bookingMonth, setBookingMonth] = useState(() => new Date().toISOString().slice(0, 7));
  const [bookings, setBookings] = useState<BookingRecord[]>([]);
  const [bookingId, setBookingId] = useState("");
  const [bookingStatus, setBookingStatus] = useState<
    | ""
    | "DRAFT"
    | "RESERVED"
    | "CHECKED_OUT"
    | "RETURNED_AUDITS_PENDING"
    | "REVIEW_REQUIRED"
    | "COMPLETED"
    | "CANCELLED"
  >("");
  const [repair, setRepair] = useState<"" | "true" | "false">("");
  const [auditStatus, setAuditStatus] = useState("");
  const [metadata, setMetadata] = useState<"" | "true" | "false">("");
  const [availability, setAvailability] = useState("");
  const advancedKey = JSON.stringify([
    includeArchived,
    location,
    condition,
    lifecycle,
    bookingId,
    bookingStatus,
    repair,
    auditStatus,
    metadata,
    availability,
  ]);
  const [sort, setSort] = useState<SortKey>("name");
  const [direction, setDirection] = useState<"asc" | "desc">("asc");
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(false);
  const requestId = useRef(0);
  const queryKey = JSON.stringify([
    category,
    containerOnly,
    direction,
    includeInactive,
    query,
    sort,
    advancedKey,
  ]);
  const [loadedQueryKey, setLoadedQueryKey] = useState<string>();
  const currentAssets = loadedQueryKey === queryKey ? assets : [];
  const currentCursor = loadedQueryKey === queryKey ? nextCursor : undefined;
  const refreshing = loading || loadedQueryKey !== queryKey;
  const invalidateRequests = useCallback(() => {
    ++requestId.current;
  }, []);

  const load = useCallback(
    async (cursor?: string, append = false) => {
      const request = ++requestId.current;
      setLoading(true);
      const result = await searchAssets({
        query: query || undefined,
        category: category || undefined,
        containerOnly: containerOnly === "" ? undefined : containerOnly === "containers",
        includeInactive,
        includeArchived,
        locationId: location || undefined,
        condition: condition || undefined,
        lifecycle: lifecycle || undefined,
        bookingId: bookingId || undefined,
        bookingStatus: bookingStatus || undefined,
        openRepair: repair ? repair === "true" : undefined,
        auditStatus: auditStatus || undefined,
        metadataIncomplete: metadata ? metadata === "true" : undefined,
        availability: availability || undefined,
        sort,
        direction,
        cursor,
        limit: 50,
      });
      if (request !== requestId.current) return;
      setLoading(false);
      setLoadedQueryKey(queryKey);
      if (result.kind === "error") {
        if (!append) {
          setAssets([]);
          setNextCursor(undefined);
        }
        setError(errorMessage(result.error));
        return;
      }
      setAssets((current) => (append ? [...current, ...result.data.items] : result.data.items));
      setNextCursor(result.data.nextCursor);
      setError(undefined);
    },
    [
      category,
      containerOnly,
      direction,
      includeInactive,
      includeArchived,
      location,
      condition,
      lifecycle,
      bookingId,
      bookingStatus,
      repair,
      auditStatus,
      metadata,
      availability,
      query,
      queryKey,
      sort,
      setLoading,
      setError,
    ],
  );

  useEffect(() => {
    if (!/^\d{4}-\d{2}$/.test(bookingMonth)) return;
    let current = true;
    const from = new Date(`${bookingMonth}-01T00:00:00Z`);
    const until = new Date(Date.UTC(from.getUTCFullYear(), from.getUTCMonth() + 1, 1));
    void listBookings(from.toISOString(), until.toISOString()).then((result) => {
      if (current && result.kind === "ok") setBookings(result.data);
    });
    return () => {
      current = false;
    };
  }, [bookingMonth]);
  useEffect(() => {
    void listLocations().then((result) => {
      if (result.kind === "ok") setLocations(result.data);
    });
    void listCategories().then((r) => {
      if (r.kind === "ok") setCategories(r.data);
    });
  }, []);
  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 220);
    return () => {
      window.clearTimeout(timer);
      invalidateRequests();
    };
  }, [invalidateRequests, load]);
  const title = useMemo(
    () =>
      `${containersOnly ? "Containers" : "Assets"}${currentAssets.length ? ` (${currentAssets.length}${currentCursor ? "+" : ""})` : ""}`,
    [containersOnly, currentAssets.length, currentCursor],
  );
  const changeSort = (key: SortKey) => {
    if (key === sort) setDirection((d) => (d === "asc" ? "desc" : "asc"));
    else {
      setSort(key);
      setDirection("asc");
    }
  };
  const head = (label: string, key: SortKey) => (
    <TableSortLabel
      aria-label={`Sort by ${label.toLowerCase()}`}
      active={sort === key}
      direction={sort === key ? direction : "asc"}
      onClick={() => changeSort(key)}
    >
      {label}
    </TableSortLabel>
  );
  return (
    <>
      <PageHeading
        title={title}
        description={
          containersOnly
            ? "Every container available in this inventory."
            : "Every individually tracked physical unit, including containers."
        }
      />
      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <Stack direction={{ xs: "column", md: "row" }} spacing={1.5}>
          <TextField
            label="Search assets"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            sx={{ minWidth: 210 }}
          />
          <TextField
            select
            label="Category"
            value={category}
            onChange={(e) => setCategory(e.target.value)}
            sx={{ minWidth: 170 }}
          >
            <MenuItem value="">All categories</MenuItem>
            <MenuItem value="Default">Default</MenuItem>
            {categories.map((item) => (
              <MenuItem key={item.id} value={item.id}>
                {item.name}
              </MenuItem>
            ))}
          </TextField>
          {!containersOnly ? (
            <TextField
              select
              label="Type"
              value={containerOnly}
              onChange={(e) => setContainerOnly(e.target.value)}
              sx={{ minWidth: 170 }}
            >
              <MenuItem value="">Equipment and containers</MenuItem>
              <MenuItem value="equipment">Equipment</MenuItem>
              <MenuItem value="containers">Containers</MenuItem>
            </TextField>
          ) : null}
          <FormControlLabel
            control={
              <Switch
                checked={includeInactive}
                onChange={(e) => setIncludeInactive(e.target.checked)}
              />
            }
            label="Include inactive"
          />
        </Stack>
        <Stack
          direction={{ xs: "column", md: "row" }}
          spacing={1.5}
          sx={{ mt: 2, flexWrap: "wrap" }}
        >
          <TextField
            select
            label="Location"
            value={location}
            onChange={(event) => setLocation(event.target.value)}
            sx={{ minWidth: 190 }}
          >
            <MenuItem value="">All locations</MenuItem>
            {locations
              .filter((item) => !item.archived || item.id === location)
              .map((item) => (
                <MenuItem value={item.id} key={item.id}>
                  {item.effectivePath}
                </MenuItem>
              ))}
          </TextField>
          <TextField
            select
            label="Condition"
            value={condition}
            onChange={(event) => setCondition(event.target.value as typeof condition)}
            sx={{ minWidth: 150 }}
          >
            <MenuItem value="">Any condition</MenuItem>
            {["GOOD", "DAMAGED"].map((value) => (
              <MenuItem value={value} key={value}>
                {value}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            label="Lifecycle"
            value={lifecycle}
            onChange={(event) => setLifecycle(event.target.value as typeof lifecycle)}
            sx={{ minWidth: 150 }}
          >
            <MenuItem value="">Default active</MenuItem>
            {["ACTIVE", "RETIRED", "LOST", "DESTROYED"].map((value) => (
              <MenuItem value={value} key={value}>
                {value}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            label="Physical readiness"
            value={availability}
            onChange={(event) => setAvailability(event.target.value)}
            sx={{ minWidth: 180 }}
          >
            <MenuItem value="">Any readiness</MenuItem>
            <MenuItem value="AVAILABLE">Available</MenuItem>
            <MenuItem value="UNAVAILABLE">Unavailable</MenuItem>
          </TextField>
          <FormControlLabel
            label="Include archived"
            control={
              <Switch
                checked={includeArchived}
                onChange={(event) => setIncludeArchived(event.target.checked)}
              />
            }
          />
        </Stack>
        <Stack
          direction={{ xs: "column", md: "row" }}
          spacing={1.5}
          sx={{ mt: 2, flexWrap: "wrap" }}
        >
          <TextField
            label="Event month"
            type="month"
            value={bookingMonth}
            onChange={(event) => {
              setBookingMonth(event.target.value);
              setBookingId("");
              setBookings([]);
            }}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <TextField
            select
            label="Event association"
            value={bookingId}
            onChange={(event) => setBookingId(event.target.value)}
            sx={{ minWidth: 190 }}
            helperText="Events overlapping the selected month"
          >
            <MenuItem value="">Any event</MenuItem>
            {bookings.map((booking) => (
              <MenuItem key={booking.id} value={booking.id}>
                {booking.name}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            label="Booking state"
            value={bookingStatus}
            onChange={(event) => setBookingStatus(event.target.value as typeof bookingStatus)}
            sx={{ minWidth: 170 }}
          >
            <MenuItem value="">Any booking state</MenuItem>
            {[
              "DRAFT",
              "RESERVED",
              "CHECKED_OUT",
              "RETURNED_AUDITS_PENDING",
              "REVIEW_REQUIRED",
              "COMPLETED",
              "CANCELLED",
            ].map((value) => (
              <MenuItem value={value} key={value}>
                {value.replaceAll("_", " ")}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            label="Repair"
            value={repair}
            onChange={(event) => setRepair(event.target.value as typeof repair)}
            sx={{ minWidth: 150 }}
          >
            <MenuItem value="">Any repair state</MenuItem>
            <MenuItem value="true">Open repair</MenuItem>
            <MenuItem value="false">No open repair</MenuItem>
          </TextField>
          <TextField
            select
            label="Current audit"
            value={auditStatus}
            onChange={(event) => setAuditStatus(event.target.value)}
            sx={{ minWidth: 160 }}
          >
            <MenuItem value="">Any audit state</MenuItem>
            {["READY", "BLOCKED", "IN_PROGRESS", "COMPLETED"].map((value) => (
              <MenuItem value={value} key={value}>
                {value.replaceAll("_", " ")}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            label="Metadata"
            value={metadata}
            onChange={(event) => setMetadata(event.target.value as typeof metadata)}
            sx={{ minWidth: 150 }}
          >
            <MenuItem value="">Any metadata state</MenuItem>
            <MenuItem value="true">Incomplete</MenuItem>
            <MenuItem value="false">Complete</MenuItem>
          </TextField>
        </Stack>
        <Typography variant="caption" color="text.secondary">
          Physical readiness includes current custody, repairs, audits, review and reservations for
          related contents and exact required assets. Event booking previews evaluate
          interchangeable packing capacity.
        </Typography>
      </Paper>
      {error ? (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      ) : null}
      <Paper variant="outlined" sx={{ overflow: "hidden" }} aria-busy={refreshing}>
        {refreshing ? (
          <Typography role="status" sx={{ p: 2 }}>
            Loading assets.
          </Typography>
        ) : null}
        <TableContainer>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell sortDirection={sort === "name" ? direction : false}>
                  {head("Asset", "name")}
                </TableCell>
                <TableCell sortDirection={sort === "code" ? direction : false}>
                  {head("Code", "code")}
                </TableCell>
                <TableCell>Model</TableCell>
                <TableCell>Category</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>Place</TableCell>
                <TableCell sortDirection={sort === "lifecycle" ? direction : false}>
                  {head("State", "lifecycle")}
                </TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {currentAssets.map((asset) => (
                <TableRow key={asset.id} hover>
                  <TableCell>
                    <Button
                      component={RouterLink}
                      to={`/inventory/assets/${asset.id}`}
                      sx={{ px: 0, textTransform: "none" }}
                    >
                      {asset.displayName}
                    </Button>
                  </TableCell>
                  <TableCell sx={{ fontFamily: "monospace", fontWeight: 700 }}>
                    {asset.publicCode}
                  </TableCell>
                  <TableCell>{asset.assetModelName}</TableCell>
                  <TableCell>{asset.categoryName ?? "Default"}</TableCell>
                  <TableCell>{asset.canContainAssets ? "Container" : "Equipment"}</TableCell>
                  <TableCell>{asset.placePath}</TableCell>
                  <TableCell>
                    {asset.archived ? <Chip size="small" label="Archived" /> : asset.lifecycleState}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
        {!refreshing && currentAssets.length === 0 ? (
          <Typography color="text.secondary" sx={{ p: 3 }}>
            {containersOnly
              ? "No containers match these filters."
              : "No assets match these filters."}
          </Typography>
        ) : null}
        {currentCursor ? (
          <Button
            sx={{ m: 1 }}
            onClick={() => void load(currentCursor, true)}
            disabled={refreshing}
          >
            Load more
          </Button>
        ) : null}
      </Paper>
    </>
  );
}
