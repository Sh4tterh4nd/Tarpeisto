import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import ArchiveIcon from "@mui/icons-material/Archive";
import ArrowBackIcon from "@mui/icons-material/ArrowBack";
import EditIcon from "@mui/icons-material/Edit";
import UnarchiveIcon from "@mui/icons-material/Unarchive";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Divider from "@mui/material/Divider";
import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemText from "@mui/material/ListItemText";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { Link as RouterLink, useParams } from "react-router-dom";
import { PageHeading } from "@tarpeisto/shared-ui";
import { useSession } from "../identity/useSession";
import {
  changeAssetCondition,
  changeAssetLifecycle,
  errorMessage,
  getAsset,
  getAssetModel,
  listAssetHistory,
  listCustomFieldOptions,
  listCustomFields,
  renameAsset,
  setAssetArchived,
  setAssetPurchaseDate,
  setAssetValues,
  type AssetHistoryRecord,
  type AssetRecord,
  type AssetValueInput,
  type CustomFieldOptionRecord,
  type CustomFieldRecord,
} from "./inventoryApi";
import { MediaPanel } from "./MediaPanel";
import { AssetPlacementPanel } from "./AssetPlacementPanel";
import { PackingPanel } from "./PackingPanel";
import { AssetLabelExportDialog } from "./AssetLabelExportDialog";
import { AssetReviewPanel } from "./AssetReviewPanel";

interface AssetDetailsDialogProps {
  asset: AssetRecord;
  onClose: () => void;
  onSaved: () => Promise<void>;
}

function AssetDetailsDialog({ asset, onClose, onSaved }: AssetDetailsDialogProps) {
  const [fields, setFields] = useState<CustomFieldRecord[]>();
  const [options, setOptions] = useState<Record<string, CustomFieldOptionRecord[]>>({});
  const [individualName, setIndividualName] = useState(asset.individualName ?? "");
  const [purchaseDate, setPurchaseDate] = useState(asset.purchaseDate ?? "");
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      asset.values.map((value) => [
        value.fieldId,
        value.stringValue || value.dateValue || value.optionId || "",
      ]),
    ),
  );
  const [error, setError] = useState<string>();
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    void (async () => {
      const result = await listCustomFields(asset.assetModelId);
      if (result.kind === "error") {
        setError(errorMessage(result.error));
        return;
      }
      const active = result.data.filter((field) => !field.archived);
      setFields(active);
      const loaded = await Promise.all(
        active
          .filter((field) => field.dataType === "DROPDOWN")
          .map(
            async (field) =>
              [field.id, await listCustomFieldOptions(asset.assetModelId, field.id)] as const,
          ),
      );
      const next: Record<string, CustomFieldOptionRecord[]> = {};
      for (const [fieldId, optionResult] of loaded) {
        if (optionResult.kind === "error") {
          setError(errorMessage(optionResult.error));
          return;
        }
        next[fieldId] = optionResult.data.filter((option) => !option.archived);
      }
      setOptions(next);
    })();
  }, [asset.assetModelId]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!fields || saving) return;
    setSaving(true);
    setError(undefined);
    const renamed = await renameAsset(asset.id, individualName.trim() || undefined);
    if (renamed.kind === "error") {
      setSaving(false);
      setError(errorMessage(renamed.error));
      return;
    }
    const dated = await setAssetPurchaseDate(asset.id, purchaseDate || undefined);
    if (dated.kind === "error") {
      setSaving(false);
      setError(errorMessage(dated.error));
      await onSaved();
      return;
    }
    const inputs: AssetValueInput[] = fields.map((field) => {
      const value = values[field.id] ?? "";
      if (field.dataType === "DATE") return { fieldId: field.id, dateValue: value };
      if (field.dataType === "DROPDOWN") return { fieldId: field.id, optionId: value };
      return { fieldId: field.id, stringValue: value };
    });
    const updated = await setAssetValues(asset.id, inputs);
    setSaving(false);
    if (updated.kind === "error") {
      setError(errorMessage(updated.error));
      await onSaved();
      return;
    }
    await onSaved();
    onClose();
  }

  const complete = fields?.every((field) => Boolean(values[field.id]?.trim())) ?? false;

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <Stack component="form" onSubmit={submit}>
        <DialogTitle>Edit asset details</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {error ? <Alert severity="error">{error}</Alert> : null}
            <TextField
              label="Individual name (optional)"
              value={individualName}
              onChange={(event) => setIndividualName(event.target.value)}
              autoFocus
            />
            <TextField
              label="Purchase date (optional)"
              type="date"
              value={purchaseDate}
              onChange={(event) => setPurchaseDate(event.target.value)}
              slotProps={{ inputLabel: { shrink: true } }}
            />
            {!fields ? <CircularProgress size={20} /> : null}
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
            Save details
          </Button>
        </DialogActions>
      </Stack>
    </Dialog>
  );
}

function StateDialog({
  asset,
  kind,
  onClose,
  onSaved,
}: {
  asset: AssetRecord;
  kind: "condition" | "lifecycle";
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [value, setValue] = useState<string>(
    kind === "condition" ? asset.condition : asset.lifecycleState,
  );
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string>();
  const [saving, setSaving] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (saving) return;
    setSaving(true);
    setError(undefined);
    const result =
      kind === "condition"
        ? await changeAssetCondition(
            asset.id,
            value as "GOOD" | "DAMAGED",
            reason.trim() || undefined,
          )
        : await changeAssetLifecycle(
            asset.id,
            value as "ACTIVE" | "LOST" | "DESTROYED" | "RETIRED",
            reason.trim() || undefined,
          );
    setSaving(false);
    if (result.kind === "error") {
      setError(errorMessage(result.error));
      return;
    }
    await onSaved();
    onClose();
  }

  const options =
    kind === "condition"
      ? [
          ["GOOD", "Good"],
          ["DAMAGED", "Damaged"],
        ]
      : [
          ["ACTIVE", "Active"],
          ["LOST", "Lost"],
          ["DESTROYED", "Destroyed"],
          ["RETIRED", "Retired"],
        ];

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <Stack component="form" onSubmit={submit}>
        <DialogTitle>Change {kind}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {error ? <Alert severity="error">{error}</Alert> : null}
            <TextField
              select
              label={kind === "condition" ? "Condition" : "Lifecycle"}
              value={value}
              onChange={(event) => setValue(event.target.value)}
            >
              {options.map(([optionValue, label]) => (
                <MenuItem key={optionValue} value={optionValue}>
                  {label}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="Reason (optional)"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              multiline
              minRows={2}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={saving}>
            Record change
          </Button>
        </DialogActions>
      </Stack>
    </Dialog>
  );
}

export function AssetPage() {
  const { assetId } = useParams();
  const { role } = useSession();
  const canManage = role === "OWNER" || role === "DEPUTY";
  const [asset, setAsset] = useState<AssetRecord>();
  const [history, setHistory] = useState<AssetHistoryRecord[]>([]);
  const [containerCapable, setContainerCapable] = useState(false);
  const [error, setError] = useState<{ routeId: string; message: string }>();
  const [dialog, setDialog] = useState<{
    routeId: string;
    kind: "details" | "condition" | "lifecycle";
  }>();
  const [actionError, setActionError] = useState<{ routeId: string; message: string }>();
  const [archiveBusyRouteId, setArchiveBusyRouteId] = useState<string>();
  const [labelExportOpen, setLabelExportOpen] = useState(false);
  const [placementRevision, setPlacementRevision] = useState(0);
  const loadRequest = useRef(0);

  const load = useCallback(async () => {
    const request = ++loadRequest.current;
    if (!assetId) return;
    const [assetResult, historyResult] = await Promise.all([
      getAsset(assetId),
      listAssetHistory(assetId),
    ]);
    if (request !== loadRequest.current) return;
    if (assetResult.kind === "error") {
      setAsset(undefined);
      setError({ routeId: assetId, message: errorMessage(assetResult.error) });
      return;
    }
    if (historyResult.kind === "error") {
      setAsset(undefined);
      setError({ routeId: assetId, message: errorMessage(historyResult.error) });
      return;
    }
    const modelResult = await getAssetModel(assetResult.data.assetModelId);
    if (request !== loadRequest.current) return;
    if (modelResult.kind === "error") {
      setAsset(undefined);
      setError({ routeId: assetId, message: errorMessage(modelResult.error) });
      return;
    }
    setAsset(assetResult.data);
    setHistory(historyResult.data);
    setContainerCapable(modelResult.data.canContainAssets);
    setError(undefined);
  }, [assetId]);

  useEffect(() => {
    void (async () => {
      await load();
    })();
  }, [load]);

  async function toggleArchive() {
    if (!asset || asset.id !== assetId || archiveBusyRouteId === asset.id) return;
    setArchiveBusyRouteId(asset.id);
    setActionError(undefined);
    const result = await setAssetArchived(asset.id, !asset.archived);
    if (result) {
      setActionError({ routeId: asset.id, message: errorMessage(result) });
    } else {
      await load();
      setActionError(undefined);
    }
    setArchiveBusyRouteId((busyRouteId) => (busyRouteId === asset.id ? undefined : busyRouteId));
  }

  const currentError = error && error.routeId === assetId ? error.message : undefined;
  const currentActionError =
    actionError && actionError.routeId === assetId ? actionError.message : undefined;
  const archiveBusy = archiveBusyRouteId === asset?.id;

  if (currentError) return <Alert severity="error">Could not load the asset: {currentError}</Alert>;
  if (!asset || asset.id !== assetId) {
    return (
      <Stack direction="row" spacing={2} sx={{ alignItems: "center", py: 5 }}>
        <CircularProgress size={24} />
        <Typography role="status">Loading asset.</Typography>
      </Stack>
    );
  }

  return (
    <>
      <Button
        component={RouterLink}
        to={`/inventory/models/${asset.assetModelId}`}
        startIcon={<ArrowBackIcon />}
        sx={{ mb: 1 }}
      >
        {asset.assetModelName}
      </Button>
      <PageHeading
        title={asset.displayName}
        description={`${asset.assetModelName} · unit ${asset.unitNumber}`}
        actions={
          <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap" }}>
            {!containerCapable ? (
              <Button onClick={() => setLabelExportOpen(true)}>Export label</Button>
            ) : null}
            {canManage ? (
              <Stack direction="row" spacing={1}>
                <Button
                  startIcon={<EditIcon />}
                  onClick={() => setDialog({ routeId: asset.id, kind: "details" })}
                  disabled={archiveBusy}
                >
                  Edit details
                </Button>
                <Button
                  color={asset.archived ? "primary" : "warning"}
                  startIcon={asset.archived ? <UnarchiveIcon /> : <ArchiveIcon />}
                  onClick={() => void toggleArchive()}
                  disabled={archiveBusy}
                >
                  {archiveBusy ? "Saving…" : asset.archived ? "Restore" : "Archive"}
                </Button>
              </Stack>
            ) : null}
          </Stack>
        }
      />
      {currentActionError ? (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setActionError(undefined)}>
          {currentActionError}
        </Alert>
      ) : null}
      {asset.lifecycleState === "LOST" ? (
        <Alert severity="error" sx={{ mb: 2 }}>
          This asset is marked lost. An Owner or Deputy must restore it before normal use.
        </Alert>
      ) : null}
      {asset.metadataIncomplete ? (
        <Alert severity="warning" sx={{ mb: 2 }}>
          Required model-defined values are missing. Edit details to complete this asset's metadata.
        </Alert>
      ) : null}

      <Stack spacing={3}>
        <Stack
          direction={{ xs: "column", md: "row" }}
          spacing={3}
          sx={{ alignItems: "flex-start" }}
        >
          <Paper variant="outlined" sx={{ flex: 1, width: "100%" }}>
            {asset.values.length > 0 ? (
              <>
                <Typography variant="h3" sx={{ p: 2 }}>
                  Unit values
                </Typography>
                <Divider />
                <List disablePadding>
                  {[...asset.values]
                    .sort((a, b) => a.displayOrder - b.displayOrder)
                    .map((value) => (
                      <ListItem key={value.fieldId} divider>
                        <ListItemText
                          primary={value.fieldName}
                          secondary={
                            value.stringValue || value.dateValue || value.optionValue || "Missing"
                          }
                        />
                      </ListItem>
                    ))}
                </List>
              </>
            ) : null}
            <Box sx={{ p: 2, bgcolor: "primary.main", color: "primary.contrastText" }}>
              <Typography variant="body2">Public asset code</Typography>
              <Typography
                component="div"
                sx={{
                  fontFamily: "monospace",
                  fontSize: "2rem",
                  fontWeight: 800,
                  letterSpacing: 4,
                }}
              >
                {asset.publicCode}
              </Typography>
            </Box>
            {asset.purchaseDate ? (
              <Typography sx={{ p: 2 }}>
                <Typography component="span" color="text.secondary">
                  Purchase date:{" "}
                </Typography>
                {asset.purchaseDate}
              </Typography>
            ) : null}
          </Paper>
          <Box sx={{ width: { xs: "100%", md: 360 }, flexShrink: 0 }}>
            <MediaPanel
              assetId={asset.id}
              fallbackAssetModelId={asset.assetModelId}
              containerCapable={containerCapable}
              canManage={canManage}
            />
          </Box>
        </Stack>
        <Stack
          direction={{ xs: "column", md: "row" }}
          spacing={3}
          sx={{ alignItems: "flex-start" }}
        >
          <Box sx={{ flex: 1, width: "100%" }}>
            <AssetPlacementPanel
              key={`${asset.id}-${placementRevision}`}
              assetId={asset.id}
              canManage={canManage}
            />
          </Box>
          <Paper variant="outlined" sx={{ width: { xs: "100%", md: 360 }, p: 2 }}>
            <Typography variant="h3">Current state</Typography>
            <Stack spacing={1.5} sx={{ mt: 1 }}>
              <Box>
                <Typography variant="body2" color="text.secondary">
                  Condition
                </Typography>
                <Typography sx={{ fontWeight: 700 }}>
                  {asset.condition === "GOOD" ? "Good" : "Damaged"}
                </Typography>
                {canManage ? (
                  <Button
                    size="small"
                    onClick={() => setDialog({ routeId: asset.id, kind: "condition" })}
                  >
                    Change
                  </Button>
                ) : null}
              </Box>
              <Box>
                <Typography variant="body2" color="text.secondary">
                  Lifecycle
                </Typography>
                <Typography sx={{ fontWeight: 700 }}>{asset.lifecycleState}</Typography>
                {canManage ? (
                  <Button
                    size="small"
                    onClick={() => setDialog({ routeId: asset.id, kind: "lifecycle" })}
                  >
                    Change
                  </Button>
                ) : null}
              </Box>
            </Stack>
          </Paper>
        </Stack>
        <AssetReviewPanel
          key={asset.id}
          assetId={asset.id}
          canManage={canManage}
          containerCapable={containerCapable}
          lifecycleState={asset.lifecycleState}
          sealable={asset.sealable}
          sealState={asset.sealState}
          sealVerifiedAt={asset.sealVerifiedAt ?? undefined}
          lastVerifiedAt={asset.lastVerifiedAt ?? undefined}
          replacesAssetId={asset.replacesAssetId ?? undefined}
        />
        {containerCapable ? (
          <PackingPanel
            containerAssetId={asset.id}
            canManage={canManage}
            onContentsChanged={() => setPlacementRevision((value) => value + 1)}
          />
        ) : null}
        {history.length > 0 ? (
          <Paper variant="outlined">
            <Typography variant="h3" sx={{ p: 2 }}>
              Condition and lifecycle history
            </Typography>
            <Divider />
            <List disablePadding>
              {history.map((item) => (
                <ListItem key={item.id} divider alignItems="flex-start">
                  <ListItemText
                    primary={`${item.changeType === "CONDITION" ? "Condition" : "Lifecycle"}: ${item.previousValue} → ${item.newValue}`}
                    secondary={`${new Date(item.changedAt).toLocaleString()}${item.reason ? ` · ${item.reason}` : ""}`}
                  />
                </ListItem>
              ))}
            </List>
          </Paper>
        ) : null}
      </Stack>

      {dialog?.routeId === asset.id && dialog.kind === "details" ? (
        <AssetDetailsDialog asset={asset} onClose={() => setDialog(undefined)} onSaved={load} />
      ) : null}
      {dialog?.routeId === asset.id &&
      (dialog.kind === "condition" || dialog.kind === "lifecycle") ? (
        <StateDialog
          asset={asset}
          kind={dialog.kind}
          onClose={() => setDialog(undefined)}
          onSaved={load}
        />
      ) : null}
      {labelExportOpen && !containerCapable ? (
        <AssetLabelExportDialog assetIds={[asset.id]} onClose={() => setLabelExportOpen(false)} />
      ) : null}
    </>
  );
}
