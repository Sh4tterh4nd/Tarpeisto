import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import AddIcon from "@mui/icons-material/Add";
import ArchiveIcon from "@mui/icons-material/Archive";
import Inventory2OutlinedIcon from "@mui/icons-material/Inventory2Outlined";
import UnarchiveIcon from "@mui/icons-material/Unarchive";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Divider from "@mui/material/Divider";
import FormControlLabel from "@mui/material/FormControlLabel";
import IconButton from "@mui/material/IconButton";
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
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { Link as RouterLink, useNavigate } from "react-router-dom";
import type { AppError } from "@bigcontainers/api-client";
import { PageHeading } from "@bigcontainers/shared-ui";
import { useSession } from "../identity/useSession";
import {
  createAssetModel,
  createCategory,
  errorMessage,
  listAssetModels,
  listCategories,
  setAssetModelArchived,
  setCategoryArchived,
  updateCategory,
  type AssetModelRecord,
  type CategoryRecord,
  type CreateAssetModelInput,
} from "./inventoryApi";

interface CatalogData {
  categories: CategoryRecord[];
  models: AssetModelRecord[];
}

type CatalogState =
  | { status: "loading" }
  | { status: "error"; error: AppError }
  | { status: "loaded"; data: CatalogData };

function foregroundFor(color: string): "#FFFFFF" | "#000000" {
  const value = color.replace("#", "");
  const channel = (index: number) => {
    const normalized = Number.parseInt(value.slice(index, index + 2), 16) / 255;
    return normalized <= 0.04045 ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4;
  };
  const luminance = 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4);
  const contrast = (foregroundLuminance: number) =>
    (Math.max(luminance, foregroundLuminance) + 0.05) /
    (Math.min(luminance, foregroundLuminance) + 0.05);
  // Choose the stronger of black and white. One of the two always exceeds WCAG AA's 4.5:1
  // threshold for normal text.
  return contrast(1) >= contrast(0) ? "#FFFFFF" : "#000000";
}

interface CategoryDialogProps {
  category?: CategoryRecord;
  onClose: () => void;
  onSaved: () => Promise<void>;
}

function CategoryDialog({ category, onClose, onSaved }: CategoryDialogProps) {
  const [name, setName] = useState(category?.name ?? "");
  const [color, setColor] = useState(category?.color ?? "#2F6B5C");
  const [error, setError] = useState<string>();
  const [saving, setSaving] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError(undefined);
    const result = category
      ? await updateCategory(category.id, { name: name.trim(), color })
      : await createCategory({ name: name.trim(), color });
    setSaving(false);
    if (result.kind === "error") {
      setError(errorMessage(result.error));
      return;
    }
    await onSaved();
    onClose();
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <Stack component="form" onSubmit={handleSubmit}>
        <DialogTitle>{category ? "Edit category" : "New category"}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {error ? <Alert severity="error">{error}</Alert> : null}
            <TextField
              label="Category name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              required
              autoFocus
            />
            <TextField
              label="Category color"
              type="color"
              value={color}
              onChange={(event) => setColor(event.target.value.toUpperCase())}
              slotProps={{ htmlInput: { "aria-label": "Category color" } }}
            />
            <Box
              sx={{
                bgcolor: color,
                color: foregroundFor(color),
                borderRadius: 1,
                p: 2,
                fontWeight: 700,
              }}
            >
              {name.trim() || "Category preview"}
            </Box>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={saving || !name.trim()}>
            {category ? "Save changes" : "Create category"}
          </Button>
        </DialogActions>
      </Stack>
    </Dialog>
  );
}

interface AssetModelDialogProps {
  categories: CategoryRecord[];
  onClose: () => void;
  onCreated: (model: AssetModelRecord) => void;
}

function AssetModelDialog({ categories, onClose, onCreated }: AssetModelDialogProps) {
  const availableCategories = categories.filter((category) => !category.archived);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [categoryId, setCategoryId] = useState(availableCategories[0]?.id ?? "");
  const [replacementUrl, setReplacementUrl] = useState("");
  const [trackingMode, setTrackingMode] =
    useState<CreateAssetModelInput["trackingMode"]>("SERIALIZED_ASSET");
  const [stockUnitLabel, setStockUnitLabel] = useState("");
  const [lowStockThreshold, setLowStockThreshold] = useState("");
  const [canContainAssets, setCanContainAssets] = useState(false);
  const [error, setError] = useState<string>();
  const [saving, setSaving] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const input: CreateAssetModelInput = {
      name: name.trim(),
      description: description.trim() || undefined,
      categoryId,
      replacementUrl: replacementUrl.trim() || undefined,
      trackingMode,
      stockUnitLabel: trackingMode === "QUANTITY_STOCK" ? stockUnitLabel.trim() : undefined,
      lowStockThreshold:
        trackingMode === "QUANTITY_STOCK" && lowStockThreshold !== ""
          ? Number(lowStockThreshold)
          : undefined,
      canContainAssets: trackingMode === "SERIALIZED_ASSET" && canContainAssets,
    };
    setSaving(true);
    setError(undefined);
    const result = await createAssetModel(input);
    setSaving(false);
    if (result.kind === "error") {
      setError(errorMessage(result.error));
      return;
    }
    onCreated(result.data);
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <Stack component="form" onSubmit={handleSubmit}>
        <DialogTitle>New asset model</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {error ? <Alert severity="error">{error}</Alert> : null}
            <TextField
              label="Model name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              required
              autoFocus
            />
            <TextField
              label="Description"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              multiline
              minRows={2}
            />
            <TextField
              select
              label="Category"
              value={categoryId}
              onChange={(event) => setCategoryId(event.target.value)}
              required
            >
              {availableCategories.map((category) => (
                <MenuItem key={category.id} value={category.id}>
                  {category.name}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="Replacement URL"
              type="url"
              value={replacementUrl}
              onChange={(event) => setReplacementUrl(event.target.value)}
            />
            <TextField
              select
              label="Tracking mode"
              value={trackingMode}
              onChange={(event) => {
                const mode = event.target.value as CreateAssetModelInput["trackingMode"];
                setTrackingMode(mode);
                if (mode === "QUANTITY_STOCK") setCanContainAssets(false);
              }}
            >
              <MenuItem value="SERIALIZED_ASSET">Individually tracked assets</MenuItem>
              <MenuItem value="QUANTITY_STOCK">Quantity stock</MenuItem>
            </TextField>
            {trackingMode === "QUANTITY_STOCK" ? (
              <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
                <TextField
                  label="Stock unit"
                  value={stockUnitLabel}
                  onChange={(event) => setStockUnitLabel(event.target.value)}
                  required
                  placeholder="roll"
                  fullWidth
                />
                <TextField
                  label="Low-stock threshold"
                  type="number"
                  value={lowStockThreshold}
                  onChange={(event) => setLowStockThreshold(event.target.value)}
                  slotProps={{ htmlInput: { min: 0, step: 0.001 } }}
                  fullWidth
                />
              </Stack>
            ) : (
              <FormControlLabel
                control={
                  <Switch
                    checked={canContainAssets}
                    onChange={(event) => setCanContainAssets(event.target.checked)}
                  />
                }
                label="Units of this model can contain assets"
              />
            )}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            type="submit"
            variant="contained"
            disabled={
              saving ||
              !name.trim() ||
              !categoryId ||
              (trackingMode === "QUANTITY_STOCK" && !stockUnitLabel.trim())
            }
          >
            Create model
          </Button>
        </DialogActions>
      </Stack>
    </Dialog>
  );
}

export function CatalogPage() {
  const navigate = useNavigate();
  const { role } = useSession();
  const canManage = role === "OWNER" || role === "DEPUTY";
  const [state, setState] = useState<CatalogState>({ status: "loading" });
  const [showArchived, setShowArchived] = useState(false);
  const [categoryDialog, setCategoryDialog] = useState<CategoryRecord | "new">();
  const [modelDialogOpen, setModelDialogOpen] = useState(false);
  const [actionError, setActionError] = useState<string>();

  const load = useCallback(async () => {
    const [categoryResult, modelResult] = await Promise.all([listCategories(), listAssetModels()]);
    if (categoryResult.kind === "error") {
      setState({ status: "error", error: categoryResult.error });
      return;
    }
    if (modelResult.kind === "error") {
      setState({ status: "error", error: modelResult.error });
      return;
    }
    setState({
      status: "loaded",
      data: { categories: categoryResult.data, models: modelResult.data },
    });
  }, []);

  useEffect(() => {
    void (async () => {
      await load();
    })();
  }, [load]);

  const visibleModels = useMemo(
    () =>
      state.status === "loaded"
        ? state.data.models.filter((model) => showArchived || !model.archived)
        : [],
    [showArchived, state],
  );

  async function toggleCategory(category: CategoryRecord) {
    setActionError(undefined);
    const error = await setCategoryArchived(category.id, !category.archived);
    if (error) {
      setActionError(errorMessage(error));
      await load();
    } else await load();
  }

  async function toggleModel(model: AssetModelRecord) {
    setActionError(undefined);
    const error = await setAssetModelArchived(model.id, !model.archived);
    if (error) {
      setActionError(errorMessage(error));
      await load();
    } else await load();
  }

  return (
    <>
      <PageHeading
        title="Inventory catalog"
        description="Define what the team owns, then open a model to manage its units or stock."
        actions={
          canManage ? (
            <Stack direction="row" spacing={1}>
              <Button startIcon={<AddIcon />} onClick={() => setCategoryDialog("new")}>
                Category
              </Button>
              <Button
                variant="contained"
                startIcon={<AddIcon />}
                onClick={() => setModelDialogOpen(true)}
                disabled={
                  state.status !== "loaded" || state.data.categories.every((item) => item.archived)
                }
              >
                Asset model
              </Button>
            </Stack>
          ) : undefined
        }
      />

      {actionError ? (
        <Alert severity="error" onClose={() => setActionError(undefined)} sx={{ mb: 2 }}>
          {actionError}
        </Alert>
      ) : null}
      {state.status === "loading" ? (
        <Stack direction="row" spacing={2} sx={{ alignItems: "center", py: 5 }}>
          <CircularProgress size={24} />
          <Typography role="status">Loading inventory catalog.</Typography>
        </Stack>
      ) : null}
      {state.status === "error" ? (
        <Alert severity="error">Could not load the catalog: {errorMessage(state.error)}</Alert>
      ) : null}
      {state.status === "loaded" ? (
        <Stack spacing={3}>
          <Paper variant="outlined" sx={{ overflow: "hidden" }}>
            <Stack
              direction={{ xs: "column", sm: "row" }}
              sx={{ alignItems: { sm: "center" }, justifyContent: "space-between", p: 2 }}
              spacing={1}
            >
              <Box>
                <Typography variant="h3">Categories</Typography>
                <Typography variant="body2" color="text.secondary">
                  Color stays visible on labels and packing sheets.
                </Typography>
              </Box>
              <FormControlLabel
                control={
                  <Switch
                    checked={showArchived}
                    onChange={(event) => setShowArchived(event.target.checked)}
                  />
                }
                label="Show archived"
              />
            </Stack>
            <Divider />
            <Stack direction="row" sx={{ gap: 1, p: 2, flexWrap: "wrap" }}>
              {state.data.categories
                .filter((category) => showArchived || !category.archived)
                .map((category) => (
                  <Chip
                    key={category.id}
                    label={`${category.name}${category.archived ? " · archived" : ""}`}
                    onClick={canManage ? () => setCategoryDialog(category) : undefined}
                    onDelete={canManage ? () => void toggleCategory(category) : undefined}
                    deleteIcon={category.archived ? <UnarchiveIcon /> : <ArchiveIcon />}
                    sx={{
                      bgcolor: category.color,
                      color: foregroundFor(category.color),
                      fontWeight: 700,
                      "& .MuiChip-deleteIcon": { color: "inherit" },
                    }}
                  />
                ))}
              {state.data.categories.length === 0 ? (
                <Typography color="text.secondary">
                  Create a category before adding the first asset model.
                </Typography>
              ) : null}
            </Stack>
          </Paper>

          <Paper variant="outlined" sx={{ overflow: "hidden" }}>
            <Box sx={{ p: 2 }}>
              <Typography variant="h3">Asset models</Typography>
              <Typography variant="body2" color="text.secondary">
                One definition for a serialized unit type or a quantity-tracked supply.
              </Typography>
            </Box>
            <Divider />
            {visibleModels.length === 0 ? (
              <Stack sx={{ alignItems: "center", py: 6, px: 2 }} spacing={1}>
                <Inventory2OutlinedIcon color="disabled" sx={{ fontSize: 48 }} />
                <Typography variant="h3">No asset models yet</Typography>
                <Typography color="text.secondary" sx={{ textAlign: "center" }}>
                  {canManage
                    ? "Create the first model to begin tracking equipment or supplies."
                    : "An Owner or Deputy can create the first model."}
                </Typography>
              </Stack>
            ) : (
              <TableContainer>
                <Table>
                  <TableHead>
                    <TableRow>
                      <TableCell>Model</TableCell>
                      <TableCell>Category</TableCell>
                      <TableCell>Tracking</TableCell>
                      <TableCell align="right">Action</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {visibleModels.map((model) => {
                      const category = state.data.categories.find(
                        (item) => item.id === model.categoryId,
                      );
                      return (
                        <TableRow key={model.id} hover>
                          <TableCell>
                            <Button
                              component={RouterLink}
                              to={`/inventory/models/${model.id}`}
                              sx={{ justifyContent: "flex-start", textTransform: "none", px: 0 }}
                            >
                              {model.name}
                            </Button>
                            {model.archived ? (
                              <Chip size="small" label="Archived" sx={{ ml: 1 }} />
                            ) : null}
                          </TableCell>
                          <TableCell>{category?.name ?? "Unknown"}</TableCell>
                          <TableCell>
                            {model.trackingMode === "SERIALIZED_ASSET"
                              ? model.canContainAssets
                                ? "Serialized container"
                                : "Serialized asset"
                              : `Quantity · ${model.stockUnitLabel}`}
                          </TableCell>
                          <TableCell align="right">
                            {canManage ? (
                              <IconButton
                                aria-label={`${model.archived ? "Restore" : "Archive"} ${model.name}`}
                                onClick={() => void toggleModel(model)}
                              >
                                {model.archived ? <UnarchiveIcon /> : <ArchiveIcon />}
                              </IconButton>
                            ) : null}
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </Paper>
        </Stack>
      ) : null}

      {categoryDialog ? (
        <CategoryDialog
          category={categoryDialog === "new" ? undefined : categoryDialog}
          onClose={() => setCategoryDialog(undefined)}
          onSaved={load}
        />
      ) : null}
      {modelDialogOpen && state.status === "loaded" ? (
        <AssetModelDialog
          categories={state.data.categories}
          onClose={() => setModelDialogOpen(false)}
          onCreated={(model) => navigate(`/inventory/models/${model.id}`)}
        />
      ) : null}
    </>
  );
}
