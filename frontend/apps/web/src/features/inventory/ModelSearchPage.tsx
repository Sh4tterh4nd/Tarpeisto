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
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TableSortLabel from "@mui/material/TableSortLabel";
import Typography from "@mui/material/Typography";
import { Link as RouterLink } from "react-router-dom";
import { PageHeading } from "@tarpeisto/shared-ui";
import { useSession } from "../identity/useSession";
import { listCategories, type CategoryRecord } from "./inventoryApi";
import { searchModels, type ModelSearchRecord } from "./catalogSearchApi";

export function ModelSearchPage() {
  const { role } = useSession();
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState("");
  const [mode, setMode] = useState<"" | "SERIALIZED_ASSET" | "QUANTITY_STOCK">("");
  const [sort, setSort] = useState<"name" | "category" | "tracking">("name");
  const [direction, setDirection] = useState<"asc" | "desc">("asc");
  const [archived, setArchived] = useState(false);
  const [categories, setCategories] = useState<CategoryRecord[]>([]);
  const [items, setItems] = useState<ModelSearchRecord[]>([]);
  const [cursor, setCursor] = useState<string>();
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(false);
  const epoch = useRef(0);
  const invalidateRequests = useCallback(() => {
    ++epoch.current;
  }, []);
  const queryKey = JSON.stringify([query, category, mode, archived, sort, direction]);
  const [loadedQueryKey, setLoadedQueryKey] = useState<string>();
  const currentItems = loadedQueryKey === queryKey ? items : [];
  const currentCursor = loadedQueryKey === queryKey ? cursor : undefined;
  const refreshing = loading || loadedQueryKey !== queryKey;
  const heading = (label: string, key: typeof sort) => (
    <TableSortLabel
      aria-label={`Sort by ${label.toLowerCase()}`}
      active={sort === key}
      direction={sort === key ? direction : "asc"}
      onClick={() => {
        setDirection(sort === key && direction === "asc" ? "desc" : "asc");
        setSort(key);
      }}
    >
      {label}
    </TableSortLabel>
  );
  const load = useCallback(
    async (next?: string) => {
      const generation = ++epoch.current;
      setLoading(true);
      const result = await searchModels({
        query: query || undefined,
        category: category || undefined,
        trackingMode: mode || undefined,
        includeArchived: archived,
        sort,
        direction,
        limit: 50,
        cursor: next,
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
    [archived, category, mode, query, queryKey, sort, direction],
  );
  useEffect(() => {
    void listCategories().then((result) => {
      if (result.kind === "ok") setCategories(result.data);
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
        title="Inventory models"
        description="Find equipment and consumables by model or category."
        actions={
          role === "OWNER" || role === "DEPUTY" ? (
            <Button component={RouterLink} to="/inventory/catalog" sx={{ minHeight: 44 }}>
              Manage catalog
            </Button>
          ) : undefined
        }
      />
      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <Stack direction={{ xs: "column", md: "row" }} spacing={2}>
          <TextField
            label="Search models"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
          <TextField
            select
            label="Category"
            value={category}
            onChange={(event) => setCategory(event.target.value)}
            sx={{ minWidth: 170 }}
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
            label="Tracking"
            value={mode}
            onChange={(event) => setMode(event.target.value as typeof mode)}
            sx={{ minWidth: 190 }}
          >
            <MenuItem value="">All tracking modes</MenuItem>
            <MenuItem value="SERIALIZED_ASSET">Serialized equipment</MenuItem>
            <MenuItem value="QUANTITY_STOCK">Consumables</MenuItem>
          </TextField>
          <FormControlLabel
            label="Include archived"
            control={
              <Switch checked={archived} onChange={(event) => setArchived(event.target.checked)} />
            }
          />
        </Stack>
      </Paper>
      {error ? (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      ) : null}
      {refreshing ? <Typography role="status">Loading models.</Typography> : null}
      <TableContainer component={Paper} variant="outlined" aria-busy={refreshing}>
        <Table aria-label="Inventory models">
          <TableHead>
            <TableRow>
              <TableCell>{heading("Name", "name")}</TableCell>
              <TableCell>{heading("Category", "category")}</TableCell>
              <TableCell>{heading("Tracking", "tracking")}</TableCell>
              <TableCell>State</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {currentItems.map((model) => (
              <TableRow key={model.id}>
                <TableCell>
                  <Button
                    component={RouterLink}
                    to={`/inventory/models/${model.id}`}
                    sx={{ justifyContent: "flex-start", p: 0, minHeight: 44 }}
                  >
                    {model.name}
                  </Button>
                </TableCell>
                <TableCell>{model.categoryName}</TableCell>
                <TableCell>
                  {model.trackingMode === "QUANTITY_STOCK"
                    ? `Consumable (${model.stockUnitLabel})`
                    : "Serialized equipment"}
                </TableCell>
                <TableCell>
                  {model.archived ? <Chip label="Archived" size="small" /> : "Active"}
                </TableCell>
              </TableRow>
            ))}
            {!refreshing && !currentItems.length ? (
              <TableRow>
                <TableCell colSpan={4}>No models match these filters.</TableCell>
              </TableRow>
            ) : null}
          </TableBody>
        </Table>
      </TableContainer>
      {currentCursor ? (
        <Button
          disabled={refreshing}
          onClick={() => void load(currentCursor)}
          sx={{ mt: 2, minHeight: 44 }}
        >
          Load more models
        </Button>
      ) : null}
    </>
  );
}
