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
import { Link as RouterLink } from "react-router-dom";
import { PageHeading } from "@tarpeisto/shared-ui";
import {
  errorMessage,
  listCategories,
  searchAssets,
  type AssetSearchRecord,
  type CategoryRecord,
} from "./inventoryApi";

type SortKey = "name" | "code" | "condition" | "lifecycle";

export function AssetsPage() {
  const [assets, setAssets] = useState<AssetSearchRecord[]>([]);
  const [categories, setCategories] = useState<CategoryRecord[]>([]);
  const [nextCursor, setNextCursor] = useState<string>();
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState("");
  const [containerOnly, setContainerOnly] = useState<string>("");
  const [includeInactive, setIncludeInactive] = useState(false);
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
    [category, containerOnly, direction, includeInactive, query, queryKey, sort],
  );

  useEffect(() => {
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
      `Assets${currentAssets.length ? ` (${currentAssets.length}${currentCursor ? "+" : ""})` : ""}`,
    [currentAssets.length, currentCursor],
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
        description="Every individually tracked physical unit, including containers."
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
            No assets match these filters.
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
