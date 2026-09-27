import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import AddIcon from "@mui/icons-material/Add";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
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
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { Link as RouterLink } from "react-router-dom";
import {
  createAsset,
  createAssetsBulk,
  errorMessage,
  listAssets,
  listCustomFieldOptions,
  listCustomFields,
  type AssetRecord,
  type AssetValueInput,
  type CustomFieldOptionRecord,
  type CustomFieldRecord,
} from "./inventoryApi";
import { AssetLabelExportDialog } from "./AssetLabelExportDialog";

interface SerializedAssetsPanelProps {
  assetModelId: string;
  modelName: string;
  containerCapable: boolean;
  canManage: boolean;
}

interface CreateAssetDialogProps extends SerializedAssetsPanelProps {
  onClose: () => void;
  onCreated: () => Promise<void>;
}

function CreateAssetDialog({
  assetModelId,
  modelName,
  containerCapable,
  onClose,
  onCreated,
}: CreateAssetDialogProps) {
  const [fields, setFields] = useState<CustomFieldRecord[]>();
  const [options, setOptions] = useState<Record<string, CustomFieldOptionRecord[]>>({});
  const [individualName, setIndividualName] = useState("");
  const [purchaseDate, setPurchaseDate] = useState("");
  const [values, setValues] = useState<Record<string, string>>({});
  const [error, setError] = useState<string>();
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    void (async () => {
      const fieldResult = await listCustomFields(assetModelId);
      if (fieldResult.kind === "error") {
        setError(errorMessage(fieldResult.error));
        return;
      }
      const active = fieldResult.data.filter((field) => !field.archived);
      setFields(active);
      const dropdowns = active.filter((field) => field.dataType === "DROPDOWN");
      const loaded = await Promise.all(
        dropdowns.map(
          async (field) =>
            [field.id, await listCustomFieldOptions(assetModelId, field.id)] as const,
        ),
      );
      const optionMap: Record<string, CustomFieldOptionRecord[]> = {};
      for (const [fieldId, result] of loaded) {
        if (result.kind === "error") {
          setError(errorMessage(result.error));
          return;
        }
        optionMap[fieldId] = result.data.filter((option) => !option.archived);
      }
      setOptions(optionMap);
    })();
  }, [assetModelId]);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!fields || saving) return;
    const fieldValues: AssetValueInput[] = fields.map((field) => {
      const value = values[field.id] ?? "";
      if (field.dataType === "DATE") return { fieldId: field.id, dateValue: value };
      if (field.dataType === "DROPDOWN") return { fieldId: field.id, optionId: value };
      return { fieldId: field.id, stringValue: value };
    });
    setSaving(true);
    setError(undefined);
    const result = await createAsset(assetModelId, {
      individualName: individualName.trim() || undefined,
      purchaseDate: purchaseDate || undefined,
      values: fieldValues,
    });
    setSaving(false);
    if (result.kind === "error") {
      setError(errorMessage(result.error));
      return;
    }
    await onCreated();
    onClose();
  }

  const complete =
    fields !== undefined &&
    fields.every((field) => Boolean(values[field.id]?.trim())) &&
    (!containerCapable || Boolean(individualName.trim()));

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <Stack component="form" onSubmit={handleSubmit}>
        <DialogTitle>Create {modelName} unit</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {error ? <Alert severity="error">{error}</Alert> : null}
            <TextField
              label={containerCapable ? "Container name" : "Individual name (optional)"}
              value={individualName}
              onChange={(event) => setIndividualName(event.target.value)}
              required={containerCapable}
              autoFocus
            />
            <TextField
              label="Purchase date (optional)"
              type="date"
              value={purchaseDate}
              onChange={(event) => setPurchaseDate(event.target.value)}
              slotProps={{ inputLabel: { shrink: true } }}
            />
            {!fields ? (
              <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
                <CircularProgress size={20} />
                <Typography role="status">Loading unit fields.</Typography>
              </Stack>
            ) : null}
            {fields?.map((field) =>
              field.dataType === "DROPDOWN" ? (
                <TextField
                  key={field.id}
                  select
                  label={field.name}
                  value={values[field.id] ?? ""}
                  onChange={(event) =>
                    setValues((current) => ({ ...current, [field.id]: event.target.value }))
                  }
                  required
                >
                  {(options[field.id] ?? []).map((option) => (
                    <MenuItem key={option.id} value={option.id}>
                      {option.value}
                    </MenuItem>
                  ))}
                </TextField>
              ) : (
                <TextField
                  key={field.id}
                  label={field.name}
                  type={field.dataType === "DATE" ? "date" : "text"}
                  value={values[field.id] ?? ""}
                  onChange={(event) =>
                    setValues((current) => ({ ...current, [field.id]: event.target.value }))
                  }
                  required
                  slotProps={
                    field.dataType === "DATE" ? { inputLabel: { shrink: true } } : undefined
                  }
                />
              ),
            )}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={saving || !complete}>
            Create unit
          </Button>
        </DialogActions>
      </Stack>
    </Dialog>
  );
}

function BulkCreateDialog({
  assetModelId,
  modelName,
  onClose,
  onCreated,
}: Omit<CreateAssetDialogProps, "containerCapable">) {
  const [count, setCount] = useState("1");
  const [purchaseDate, setPurchaseDate] = useState("");
  const [error, setError] = useState<string>();
  const [saving, setSaving] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (saving) return;
    const parsedCount = Number(count);
    if (!Number.isInteger(parsedCount) || parsedCount < 1 || parsedCount > 500) {
      setError("Enter a whole number from 1 to 500.");
      return;
    }
    setSaving(true);
    setError(undefined);
    const result = await createAssetsBulk(assetModelId, parsedCount, purchaseDate || undefined);
    setSaving(false);
    if (result.kind === "error") {
      setError(errorMessage(result.error));
      return;
    }
    await onCreated();
    onClose();
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <Stack component="form" onSubmit={submit}>
        <DialogTitle>Bulk-create {modelName}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {error ? <Alert severity="error">{error}</Alert> : null}
            <Alert severity="info">
              Unit numbers and public codes are assigned automatically. If this model has required
              unit fields, the new units will be marked metadata incomplete until values are added.
            </Alert>
            <TextField
              label="Number of units"
              type="number"
              value={count}
              onChange={(event) => {
                setCount(event.target.value);
                setError(undefined);
              }}
              slotProps={{ htmlInput: { min: 1, max: 500 } }}
              required
              autoFocus
            />
            <TextField
              label="Purchase date (optional)"
              type="date"
              value={purchaseDate}
              onChange={(event) => setPurchaseDate(event.target.value)}
              slotProps={{ inputLabel: { shrink: true } }}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            type="submit"
            variant="contained"
            disabled={
              saving || !Number.isInteger(Number(count)) || Number(count) < 1 || Number(count) > 500
            }
          >
            Create units
          </Button>
        </DialogActions>
      </Stack>
    </Dialog>
  );
}

export function SerializedAssetsPanel(props: SerializedAssetsPanelProps) {
  const { assetModelId, modelName, containerCapable, canManage } = props;
  const [assets, setAssets] = useState<AssetRecord[]>();
  const [includeInactive, setIncludeInactive] = useState(false);
  const [error, setError] = useState<string>();
  const [createOpen, setCreateOpen] = useState(false);
  const [bulkOpen, setBulkOpen] = useState(false);
  const [labelExportOpen, setLabelExportOpen] = useState(false);
  const [selectedAssetIds, setSelectedAssetIds] = useState<Set<string>>(() => new Set());
  const loadRequest = useRef(0);

  const load = useCallback(async () => {
    const request = ++loadRequest.current;
    const result = await listAssets(assetModelId, includeInactive);
    if (request !== loadRequest.current) return;
    if (result.kind === "error") {
      setAssets(undefined);
      setError(errorMessage(result.error));
    } else {
      setAssets(result.data);
      setError(undefined);
    }
  }, [assetModelId, includeInactive]);

  useEffect(() => {
    void (async () => {
      await load();
    })();
  }, [load]);

  const visibleAssetIds = assets?.map((asset) => asset.id) ?? [];
  const selectedVisibleCount = visibleAssetIds.filter((assetId) =>
    selectedAssetIds.has(assetId),
  ).length;

  function toggleAssetSelection(assetId: string) {
    setSelectedAssetIds((current) => {
      const next = new Set(current);
      if (next.has(assetId)) next.delete(assetId);
      else next.add(assetId);
      return next;
    });
  }

  function toggleVisibleSelection() {
    setSelectedAssetIds((current) => {
      const next = new Set(current);
      const allVisibleSelected = visibleAssetIds.every((assetId) => next.has(assetId));
      for (const assetId of visibleAssetIds) {
        if (allVisibleSelected) next.delete(assetId);
        else next.add(assetId);
      }
      return next;
    });
  }

  return (
    <>
      <Paper variant="outlined" sx={{ overflow: "hidden" }}>
        <Stack
          direction={{ xs: "column", sm: "row" }}
          spacing={2}
          sx={{ alignItems: { sm: "center" }, justifyContent: "space-between", p: 2 }}
        >
          <Box>
            <Typography variant="h3">Physical units</Typography>
            <Typography variant="body2" color="text.secondary">
              Every unit has its own checked public code and lifecycle.
            </Typography>
          </Box>
          <Stack direction="row" sx={{ gap: 1, flexWrap: "wrap" }}>
            <FormControlLabel
              control={
                <Switch
                  checked={includeInactive}
                  onChange={(event) => setIncludeInactive(event.target.checked)}
                />
              }
              label="Show inactive"
            />
            {!containerCapable ? (
              <Button
                onClick={() => setLabelExportOpen(true)}
                disabled={selectedVisibleCount === 0}
              >
                Export labels{selectedVisibleCount ? ` (${selectedVisibleCount})` : ""}
              </Button>
            ) : null}
            {canManage ? (
              <>
                {!containerCapable ? (
                  <Button onClick={() => setBulkOpen(true)}>Bulk create</Button>
                ) : null}
                <Button
                  variant="contained"
                  startIcon={<AddIcon />}
                  onClick={() => setCreateOpen(true)}
                >
                  Create unit
                </Button>
              </>
            ) : null}
          </Stack>
        </Stack>
        {containerCapable ? (
          <Alert severity="info" sx={{ mx: 2, mb: 2 }}>
            Container labels use the container contents sheet. Open a container to download it.
          </Alert>
        ) : null}
        {error ? (
          <Alert severity="error" sx={{ m: 2 }}>
            {error}
          </Alert>
        ) : null}
        {!assets ? (
          <Stack direction="row" spacing={2} sx={{ alignItems: "center", p: 3 }}>
            <CircularProgress size={20} />
            <Typography role="status">Loading physical units.</Typography>
          </Stack>
        ) : assets.length === 0 ? (
          <Typography color="text.secondary" sx={{ px: 2, pb: 3 }}>
            No units match this view.
          </Typography>
        ) : (
          <TableContainer>
            <Table>
              <TableHead>
                <TableRow>
                  <TableCell padding="checkbox">
                    <Checkbox
                      slotProps={{ input: { "aria-label": "Select all visible units" } }}
                      checked={
                        visibleAssetIds.length > 0 &&
                        selectedVisibleCount === visibleAssetIds.length
                      }
                      indeterminate={
                        selectedVisibleCount > 0 && selectedVisibleCount < visibleAssetIds.length
                      }
                      onChange={toggleVisibleSelection}
                    />
                  </TableCell>
                  <TableCell>Unit</TableCell>
                  <TableCell>Public code</TableCell>
                  <TableCell>Condition</TableCell>
                  <TableCell>Lifecycle</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {assets.map((asset) => (
                  <TableRow key={asset.id} hover>
                    <TableCell padding="checkbox">
                      <Checkbox
                        slotProps={{ input: { "aria-label": `Select ${asset.displayName}` } }}
                        checked={selectedAssetIds.has(asset.id)}
                        onChange={() => toggleAssetSelection(asset.id)}
                      />
                    </TableCell>
                    <TableCell>
                      <Button
                        component={RouterLink}
                        to={`/inventory/assets/${asset.id}`}
                        sx={{ justifyContent: "flex-start", textTransform: "none", px: 0 }}
                      >
                        {asset.displayName}
                      </Button>
                      {asset.metadataIncomplete ? (
                        <Chip
                          label="Metadata incomplete"
                          color="warning"
                          size="small"
                          sx={{ ml: 1 }}
                        />
                      ) : null}
                    </TableCell>
                    <TableCell sx={{ fontFamily: "monospace", fontWeight: 700, letterSpacing: 1 }}>
                      {asset.publicCode}
                    </TableCell>
                    <TableCell>{asset.condition === "GOOD" ? "Good" : "Damaged"}</TableCell>
                    <TableCell>{asset.archived ? "Archived" : asset.lifecycleState}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Paper>

      {createOpen ? (
        <CreateAssetDialog {...props} onClose={() => setCreateOpen(false)} onCreated={load} />
      ) : null}
      {bulkOpen ? (
        <BulkCreateDialog
          assetModelId={assetModelId}
          modelName={modelName}
          canManage={canManage}
          onClose={() => setBulkOpen(false)}
          onCreated={load}
        />
      ) : null}
      {labelExportOpen && selectedVisibleCount > 0 && !containerCapable ? (
        <AssetLabelExportDialog
          assetIds={visibleAssetIds.filter((assetId) => selectedAssetIds.has(assetId))}
          onClose={() => setLabelExportOpen(false)}
        />
      ) : null}
    </>
  );
}
