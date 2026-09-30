import { useCallback, useEffect, useRef, useState } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { Link as RouterLink } from "react-router-dom";
import { PageHeading } from "@tarpeisto/shared-ui";
import { useSession } from "../identity/useSession";
import { ArchiveButton } from "../archive/ArchiveButton";
import {
  listCategories,
  listLocations,
  type CategoryRecord,
  type LocationRecord,
} from "./inventoryApi";
import { searchStock, type StockSearchRecord } from "./catalogSearchApi";

export function StockSearchPage() {
  const { role } = useSession();
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState("");
  const [location, setLocation] = useState("");
  const [archived, setArchived] = useState(false);
  const [low, setLow] = useState(false);
  const [minimum, setMinimum] = useState("");
  const [maximum, setMaximum] = useState("");
  const [categories, setCategories] = useState<CategoryRecord[]>([]);
  const [locations, setLocations] = useState<LocationRecord[]>([]);
  const [items, setItems] = useState<StockSearchRecord[]>([]);
  const [cursor, setCursor] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const epoch = useRef(0);
  const invalidateRequests = useCallback(() => {
    ++epoch.current;
  }, []);
  const queryKey = JSON.stringify([query, category, location, archived, low, minimum, maximum]);
  const [loadedQueryKey, setLoadedQueryKey] = useState<string>();
  const currentItems = loadedQueryKey === queryKey ? items : [];
  const currentCursor = loadedQueryKey === queryKey ? cursor : undefined;
  const refreshing = loading || loadedQueryKey !== queryKey;
  const load = useCallback(
    async (next?: string) => {
      const generation = ++epoch.current;
      setLoading(true);
      const result = await searchStock({
        query: query || undefined,
        category: category || undefined,
        locationId: location || undefined,
        includeArchived: archived,
        lowStock: low || undefined,
        minimumQuantity: minimum ? Number(minimum) : undefined,
        maximumQuantity: maximum ? Number(maximum) : undefined,
        cursor: next,
        limit: 50,
      });
      if (generation !== epoch.current) return;
      setLoading(false);
      setLoadedQueryKey(queryKey);
      if (result.kind === "error") {
        if (!next) {
          setItems([]);
          setCursor(undefined);
        }
        setError(result.error.problem?.detail ?? result.error.message);
        return;
      }
      setItems((current) => (next ? [...current, ...result.data.items] : result.data.items));
      setCursor(result.data.nextCursor);
      setError(undefined);
    },
    [archived, category, location, low, minimum, maximum, query, queryKey],
  );
  useEffect(() => {
    void Promise.all([listCategories(), listLocations()]).then(([c, l]) => {
      if (c.kind === "ok") setCategories(c.data);
      if (l.kind === "ok") setLocations(l.data);
    });
  }, []);
  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 220);
    return () => {
      window.clearTimeout(timer);
      invalidateRequests();
    };
  }, [load, invalidateRequests]);
  return (
    <>
      <PageHeading
        title="Consumable stock"
        description="Search models and stock places. Low stock uses the total across active on-hand balances."
      />
      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <Stack spacing={2}>
          <Stack direction={{ xs: "column", md: "row" }} spacing={2}>
            <TextField
              label="Search consumables and places"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
            <TextField
              select
              label="Category"
              value={category}
              onChange={(event) => setCategory(event.target.value)}
              sx={{ minWidth: 160 }}
            >
              <MenuItem value="">All categories</MenuItem>
              {categories
                .filter((item) => !item.archived)
                .map((item) => (
                  <MenuItem key={item.id} value={item.id}>
                    {item.name}
                  </MenuItem>
                ))}
            </TextField>
            <TextField
              select
              label="Location"
              value={location}
              onChange={(event) => setLocation(event.target.value)}
              sx={{ minWidth: 180 }}
            >
              <MenuItem value="">All locations</MenuItem>
              {locations
                .filter((item) => !item.archived)
                .map((item) => (
                  <MenuItem key={item.id} value={item.id}>
                    {item.effectivePath}
                  </MenuItem>
                ))}
            </TextField>
          </Stack>
          <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
            <TextField
              label="Minimum quantity"
              type="number"
              value={minimum}
              onChange={(event) => setMinimum(event.target.value)}
              slotProps={{ htmlInput: { min: 0, step: "0.001" } }}
            />
            <TextField
              label="Maximum quantity"
              type="number"
              value={maximum}
              onChange={(event) => setMaximum(event.target.value)}
              slotProps={{ htmlInput: { min: 0, step: "0.001" } }}
            />
            <FormControlLabel
              label="Low stock only"
              control={<Switch checked={low} onChange={(event) => setLow(event.target.checked)} />}
            />
            <FormControlLabel
              label="Include archived"
              control={
                <Switch
                  checked={archived}
                  onChange={(event) => setArchived(event.target.checked)}
                />
              }
            />
          </Stack>
        </Stack>
      </Paper>
      {error ? (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      ) : null}
      {refreshing ? <Typography role="status">Loading consumables.</Typography> : null}
      <Paper variant="outlined" aria-busy={refreshing}>
        {currentItems.map((item) => (
          <Stack
            direction={{ xs: "column", sm: "row" }}
            spacing={2}
            key={item.id ?? item.assetModelId}
            sx={{ p: 2, borderBottom: 1, borderColor: "divider", alignItems: { sm: "center" } }}
          >
            <Stack sx={{ flex: 1 }}>
              <Button
                component={RouterLink}
                to={`/inventory/models/${item.assetModelId}`}
                sx={{ justifyContent: "flex-start", p: 0, minHeight: 44 }}
              >
                {item.assetModelName}
              </Button>
              <Typography variant="body2" color="text.secondary">
                {item.categoryName} · {item.placePath}
              </Typography>
              <Typography>
                {item.quantity} {item.stockUnitLabel} at this place; {item.totalOnHand} on hand
              </Typography>
            </Stack>
            {item.lowStock ? <Chip color="warning" label="Low stock" size="small" /> : null}
            {item.archived ? <Chip label="Archived" size="small" /> : null}
            {item.id &&
            (role === "OWNER" || role === "DEPUTY") &&
            (item.archived || Number(item.quantity) === 0) ? (
              <ArchiveButton
                kind="stock"
                id={item.id}
                archived={item.archived}
                version={item.version}
                label="balance"
                onChanged={() => load()}
              />
            ) : null}
          </Stack>
        ))}
        {!refreshing && !currentItems.length ? (
          <Typography sx={{ p: 2 }}>No consumables match these filters.</Typography>
        ) : null}
      </Paper>
      {currentCursor ? (
        <Button
          disabled={refreshing}
          onClick={() => void load(currentCursor)}
          sx={{ mt: 2, minHeight: 44 }}
        >
          Load more consumables
        </Button>
      ) : null}
    </>
  );
}
