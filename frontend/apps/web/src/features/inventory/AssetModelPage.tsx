import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import ArrowBackIcon from "@mui/icons-material/ArrowBack";
import EditIcon from "@mui/icons-material/Edit";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import FormControlLabel from "@mui/material/FormControlLabel";
import Link from "@mui/material/Link";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { Link as RouterLink, useParams } from "react-router-dom";
import { PageHeading } from "@tarpeisto/shared-ui";
import { useSession } from "../identity/useSession";
import { ConsumableStockPanel } from "./ConsumableStockPanel";
import {
  changeAssetModelCategory,
  changeAssetModelReplacementUrl,
  changeAssetModelTrackingMode,
  errorMessage,
  getAssetModel,
  listCategories,
  setAssetModelCanContainAssets,
  updateAssetModelDetails,
  type AssetModelRecord,
  type CategoryRecord,
} from "./inventoryApi";
import { ModelFieldsPanel } from "./ModelFieldsPanel";
import { MediaPanel } from "./MediaPanel";
import { SerializedAssetsPanel } from "./SerializedAssetsPanel";

interface ModelPageData {
  model: AssetModelRecord;
  categories: CategoryRecord[];
}

function EditModelDialog({
  data,
  onClose,
  onSaved,
}: {
  data: ModelPageData;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const { model, categories } = data;
  const [name, setName] = useState(model.name);
  const [description, setDescription] = useState(model.description ?? "");
  const [categoryId, setCategoryId] = useState(model.categoryId ?? "");
  const [replacementUrl, setReplacementUrl] = useState(model.replacementUrl ?? "");
  const [trackingMode, setTrackingMode] = useState(model.trackingMode);
  const [stockUnitLabel, setStockUnitLabel] = useState(model.stockUnitLabel ?? "");
  const [lowStockThreshold, setLowStockThreshold] = useState(
    model.lowStockThreshold === undefined || model.lowStockThreshold === null
      ? ""
      : String(model.lowStockThreshold),
  );
  const [canContainAssets, setCanContainAssets] = useState(model.canContainAssets);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (saving || !name.trim()) return;
    setSaving(true);
    setError(undefined);
    const details = await updateAssetModelDetails(
      model.id,
      name.trim(),
      description.trim() || undefined,
    );
    if (details.kind === "error") {
      setSaving(false);
      setError(errorMessage(details.error));
      return;
    }
    const persistedChange = true;
    if (categoryId !== (model.categoryId ?? "")) {
      const category = await changeAssetModelCategory(model.id, categoryId || undefined);
      if (category.kind === "error") {
        setSaving(false);
        setError(errorMessage(category.error));
        if (persistedChange) await onSaved();
        return;
      }
    }
    if (replacementUrl !== (model.replacementUrl ?? "")) {
      const replacement = await changeAssetModelReplacementUrl(
        model.id,
        replacementUrl.trim() || undefined,
      );
      if (replacement.kind === "error") {
        setSaving(false);
        setError(errorMessage(replacement.error));
        if (persistedChange) await onSaved();
        return;
      }
    }
    if (
      trackingMode !== model.trackingMode ||
      stockUnitLabel !== (model.stockUnitLabel ?? "") ||
      lowStockThreshold !==
        (model.lowStockThreshold === undefined || model.lowStockThreshold === null
          ? ""
          : String(model.lowStockThreshold))
    ) {
      const tracking = await changeAssetModelTrackingMode(
        model.id,
        trackingMode,
        trackingMode === "QUANTITY_STOCK" ? stockUnitLabel.trim() : undefined,
        trackingMode === "QUANTITY_STOCK" && lowStockThreshold !== ""
          ? Number(lowStockThreshold)
          : undefined,
      );
      if (tracking.kind === "error") {
        setSaving(false);
        setError(errorMessage(tracking.error));
        if (persistedChange) await onSaved();
        return;
      }
    }
    if (canContainAssets !== model.canContainAssets) {
      const containment = await setAssetModelCanContainAssets(model.id, canContainAssets);
      if (containment.kind === "error") {
        setSaving(false);
        setError(errorMessage(containment.error));
        if (persistedChange) await onSaved();
        return;
      }
    }
    setSaving(false);
    await onSaved();
    onClose();
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <Stack component="form" onSubmit={submit}>
        <DialogTitle>Edit model</DialogTitle>
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
              minRows={3}
            />
            <TextField
              select
              label="Category"
              value={categoryId}
              onChange={(event) => setCategoryId(event.target.value)}
            >
              <MenuItem value="">Default</MenuItem>
              {categories
                .filter((category) => !category.archived || category.id === model.categoryId)
                .map((category) => (
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
                const next = event.target.value as AssetModelRecord["trackingMode"];
                setTrackingMode(next);
                if (next === "QUANTITY_STOCK") setCanContainAssets(false);
              }}
            >
              <MenuItem value="SERIALIZED_ASSET">Serialized asset</MenuItem>
              <MenuItem value="QUANTITY_STOCK">Quantity stock</MenuItem>
            </TextField>
            {trackingMode === "QUANTITY_STOCK" ? (
              <>
                <TextField
                  label="Stock unit"
                  value={stockUnitLabel}
                  onChange={(event) => setStockUnitLabel(event.target.value)}
                  required
                />
                <TextField
                  label="Low-stock threshold (optional)"
                  type="number"
                  value={lowStockThreshold}
                  onChange={(event) => setLowStockThreshold(event.target.value)}
                  slotProps={{ htmlInput: { min: 0, step: 0.001 } }}
                />
              </>
            ) : (
              <FormControlLabel
                control={
                  <Switch
                    checked={canContainAssets}
                    onChange={(event) => setCanContainAssets(event.target.checked)}
                  />
                }
                label="This model can contain assets"
              />
            )}
            <Alert severity="info">
              Changes are checked by the server: it prevents tracking and container changes once
              existing inventory or history would make them unsafe.
            </Alert>
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
              (trackingMode === "QUANTITY_STOCK" &&
                (!stockUnitLabel.trim() ||
                  (lowStockThreshold !== "" &&
                    (!Number.isFinite(Number(lowStockThreshold)) ||
                      Number(lowStockThreshold) < 0))))
            }
          >
            Save changes
          </Button>
        </DialogActions>
      </Stack>
    </Dialog>
  );
}

export function AssetModelPage() {
  const { assetModelId } = useParams();
  const { role } = useSession();
  const canManage = role === "OWNER" || role === "DEPUTY";
  const [data, setData] = useState<ModelPageData>();
  const [error, setError] = useState<{ routeId: string; message: string }>();
  const [editRouteId, setEditRouteId] = useState<string>();
  const [definitionRevision, setDefinitionRevision] = useState(0);
  const loadRequest = useRef(0);

  const load = useCallback(async () => {
    const request = ++loadRequest.current;
    if (!assetModelId) return;
    const [modelResult, categoryResult] = await Promise.all([
      getAssetModel(assetModelId),
      listCategories(),
    ]);
    if (request !== loadRequest.current) return;
    if (modelResult.kind === "error") {
      setData(undefined);
      setError({ routeId: assetModelId, message: errorMessage(modelResult.error) });
      return;
    }
    if (categoryResult.kind === "error") {
      setData(undefined);
      setError({ routeId: assetModelId, message: errorMessage(categoryResult.error) });
      return;
    }
    setData({ model: modelResult.data, categories: categoryResult.data });
    setError(undefined);
  }, [assetModelId]);

  const refreshModelAndPanels = useCallback(async () => {
    await load();
    setDefinitionRevision((revision) => revision + 1);
  }, [load]);

  useEffect(() => {
    void (async () => {
      await load();
    })();
  }, [load]);

  const currentData = data?.model.id === assetModelId ? data : undefined;
  const currentError = error && error.routeId === assetModelId ? error.message : undefined;

  if (currentError) return <Alert severity="error">Could not load the model: {currentError}</Alert>;
  if (!currentData || !assetModelId) {
    return (
      <Stack direction="row" spacing={2} sx={{ alignItems: "center", py: 5 }}>
        <CircularProgress size={24} />
        <Typography role="status">Loading asset model.</Typography>
      </Stack>
    );
  }

  const { model, categories } = currentData;
  const category = categories.find((item) => item.id === model.categoryId);

  return (
    <>
      <Button component={RouterLink} to="/inventory" startIcon={<ArrowBackIcon />} sx={{ mb: 1 }}>
        Inventory catalog
      </Button>
      <PageHeading
        title={model.name}
        description={model.description || "No model description."}
        actions={
          canManage ? (
            <Button startIcon={<EditIcon />} onClick={() => setEditRouteId(model.id)}>
              Edit model
            </Button>
          ) : undefined
        }
      />

      <Stack spacing={3}>
        <Box
          sx={{
            display: "grid",
            gridTemplateColumns: { xs: "minmax(0, 1fr)", md: "repeat(2, minmax(0, 1fr))" },
            gap: 3,
            alignItems: "start",
          }}
        >
          <Stack spacing={3} sx={{ flex: 1, minWidth: 0, width: "100%" }}>
            <Paper
              variant="outlined"
              sx={{
                borderLeft: 6,
                borderLeftColor: category?.color ?? "divider",
                p: 2,
              }}
            >
              <Stack direction={{ xs: "column", sm: "row" }} sx={{ gap: 2, flexWrap: "wrap" }}>
                <Box sx={{ minWidth: 180 }}>
                  <Typography variant="body2" color="text.secondary">
                    Category
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>{category?.name ?? "Default"}</Typography>
                </Box>
                <Box sx={{ minWidth: 180 }}>
                  <Typography variant="body2" color="text.secondary">
                    Tracking
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>
                    {model.trackingMode === "SERIALIZED_ASSET"
                      ? model.canContainAssets
                        ? "Serialized container"
                        : "Serialized asset"
                      : `Quantity in ${model.stockUnitLabel}`}
                  </Typography>
                </Box>
                <Box sx={{ minWidth: 180 }}>
                  <Typography variant="body2" color="text.secondary">
                    Replacement
                  </Typography>
                  {model.replacementUrl ? (
                    <Link href={model.replacementUrl} target="_blank" rel="noreferrer">
                      Open supplier page
                    </Link>
                  ) : (
                    <Typography>Not set</Typography>
                  )}
                </Box>
                {model.archived ? <Chip label="Archived" /> : null}
              </Stack>
            </Paper>

            {model.trackingMode === "SERIALIZED_ASSET" ? (
              <ModelFieldsPanel
                key={`fields-${model.id}-${definitionRevision}`}
                assetModelId={assetModelId}
                canManage={canManage}
                onDefinitionsChanged={() => setDefinitionRevision((revision) => revision + 1)}
              />
            ) : null}
          </Stack>
          <Box sx={{ minWidth: 0, width: "100%" }}>
            <MediaPanel assetModelId={assetModelId} canManage={canManage} />
          </Box>
        </Box>
        {model.trackingMode === "SERIALIZED_ASSET" ? (
          <>
            <SerializedAssetsPanel
              key={`assets-${model.id}-${definitionRevision}`}
              assetModelId={assetModelId}
              modelName={model.name}
              containerCapable={model.canContainAssets}
              canManage={canManage}
            />
          </>
        ) : (
          <ConsumableStockPanel
            key={`stock-${model.id}-${definitionRevision}`}
            assetModelId={assetModelId}
            canManage={canManage}
          />
        )}
      </Stack>

      {editRouteId === model.id ? (
        <EditModelDialog
          data={currentData}
          onClose={() => setEditRouteId(undefined)}
          onSaved={refreshModelAndPanels}
        />
      ) : null}
    </>
  );
}
