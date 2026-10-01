import { useCallback, useEffect, useRef, useState } from "react";
import MoreVertIcon from "@mui/icons-material/MoreVert";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import ArrowBackIcon from "@mui/icons-material/ArrowBack";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import Collapse from "@mui/material/Collapse";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Divider from "@mui/material/Divider";
import IconButton from "@mui/material/IconButton";
import Link from "@mui/material/Link";
import Menu from "@mui/material/Menu";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { Link as RouterLink, useParams } from "react-router-dom";
import { PageHeading } from "@tarpeisto/shared-ui";
import { useSession } from "../identity/useSession";
import {
  breakAssetSeal,
  errorMessage,
  getAsset,
  getAssetModel,
  getAssetPlacement,
  listAssetHistory,
  listAssetRepairs,
  setAssetArchived,
  type AssetRecord,
  type AssetModelRecord,
  type AssetPlacementRecord,
  type AssetHistoryRecord,
  type RepairRecord,
} from "./inventoryApi";
import { formatModelDescription } from "./formatModelDescription";
import { AssetContentsCard } from "./AssetContentsCard";
import { AssetDetailsEditor } from "./AssetDetailsEditor";
import { AssetRepairDialog } from "./AssetRepairDialog";
import { AssetLabelExportDialog } from "./AssetLabelExportDialog";
import { MediaPanel } from "./MediaPanel";
import { PackingPanel } from "./PackingPanel";
import { downloadPackingSheet, savePackingSheet } from "./packingSheetApi";

/** Reset the complete surface, drafts and dialogs whenever the route or authenticated identity changes. */
export function AssetPage() {
  const { assetId } = useParams();
  const { principal, role } = useSession();
  if (!assetId) return null;
  return (
    <AssetDetail
      key={`${principal?.organizationId}:${principal?.userId}:${role}:${assetId}`}
      assetId={assetId}
      canManage={role === "OWNER" || role === "DEPUTY"}
    />
  );
}
function AssetDetail({ assetId, canManage }: { assetId: string; canManage: boolean }) {
  const [asset, setAsset] = useState<AssetRecord>();
  const [model, setModel] = useState<AssetModelRecord>();
  const [placement, setPlacement] = useState<AssetPlacementRecord>();
  const [repairs, setRepairs] = useState<RepairRecord[]>([]);
  const [history, setHistory] = useState<AssetHistoryRecord[]>([]);
  const [error, setError] = useState<string>();
  const [actionError, setActionError] = useState<string>();
  const [editing, setEditing] = useState(false);
  const [expanded, setExpanded] = useState(true);
  const [menu, setMenu] = useState<HTMLElement | null>(null);
  const [archiveConfirm, setArchiveConfirm] = useState(false);
  const [repairMode, setRepairMode] = useState<"open" | "manage" | "view">();
  const [labels, setLabels] = useState(false);
  const [busy, setBusy] = useState(false);
  const [draftBusy, setDraftBusy] = useState(false);
  const [downloadBusy, setDownloadBusy] = useState(false);
  const [downloadError, setDownloadError] = useState<string>();
  const [revision, setRevision] = useState(0);
  const generation = useRef<symbol | undefined>(undefined);
  const alive = useRef(true);
  const load = useCallback(async () => {
    const epoch = Symbol();
    generation.current = epoch;
    const [ar, pr, rr, hr] = await Promise.all([
      getAsset(assetId),
      getAssetPlacement(assetId),
      listAssetRepairs(assetId),
      listAssetHistory(assetId),
    ]);
    if (!alive.current || epoch !== generation.current) return;
    const failure = [ar, pr, rr, hr].find((result) => result.kind === "error");
    if (failure?.kind === "error") {
      setError(errorMessage(failure.error));
      return;
    }
    if (ar.kind !== "ok" || pr.kind !== "ok" || rr.kind !== "ok" || hr.kind !== "ok") return;
    const mr = await getAssetModel(ar.data.assetModelId);
    if (!alive.current || epoch !== generation.current) return;
    if (mr.kind === "error") {
      setError(errorMessage(mr.error));
      return;
    }
    setAsset(ar.data);
    setModel(mr.data);
    setPlacement(pr.data);
    setRepairs(rr.data);
    setHistory(hr.data);
    setError(undefined);
  }, [assetId]);
  useEffect(() => {
    alive.current = true;
    const timer = setTimeout(() => {
      void load();
    }, 0);
    return () => {
      alive.current = false;
      generation.current = undefined;
      clearTimeout(timer);
    };
  }, [load]);
  async function saved() {
    await load();
    if (alive.current) setRevision((current) => current + 1);
  }
  async function archive() {
    if (!asset || busy) return;
    setBusy(true);
    setActionError(undefined);
    const failure = await setAssetArchived(assetId, !asset.archived);
    if (!alive.current) return;
    setBusy(false);
    if (failure) {
      setActionError(errorMessage(failure));
      return;
    }
    setArchiveConfirm(false);
    setEditing(false);
    await saved();
  }
  async function openSeal() {
    if (busy) return;
    setBusy(true);
    setActionError(undefined);
    const failure = await breakAssetSeal(assetId);
    if (!alive.current) return;
    setBusy(false);
    if (failure) {
      setActionError(errorMessage(failure));
      return;
    }
    await saved();
  }
  async function download() {
    if (downloadBusy) return;
    setDownloadBusy(true);
    setDownloadError(undefined);
    const result = await downloadPackingSheet(assetId);
    if (!alive.current) return;
    setDownloadBusy(false);
    if (result.kind === "error") {
      setDownloadError(errorMessage(result.error));
      return;
    }
    savePackingSheet(result.data, assetId);
  }
  if (!asset || !model || !placement)
    return error ? (
      <Alert
        severity="error"
        action={
          <Button color="inherit" onClick={() => void load()}>
            Retry
          </Button>
        }
      >
        Could not load the asset: {error}
      </Alert>
    ) : (
      <Stack direction="row" spacing={2} sx={{ py: 5 }}>
        <CircularProgress size={24} />
        <Typography role="status">Loading asset.</Typography>
      </Stack>
    );
  const openRepairs = repairs.filter((repair) => !repair.closedAt);
  const parentName = placement.parentContainerAssetId ? placement.effectivePath.at(-2) : undefined;
  const locationName = placement.directLocationId
    ? placement.effectivePath.slice(0, -1).join(" / ")
    : undefined;
  return (
    <>
      <Button
        component={RouterLink}
        to={`/inventory/models/${asset.assetModelId}`}
        startIcon={<ArrowBackIcon />}
        sx={{ mb: 1, minHeight: 44 }}
      >
        {asset.assetModelName}
      </Button>
      <PageHeading
        title={asset.displayName}
        description={formatModelDescription(model.description)}
        actions={
          <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
            {!model.canContainAssets ? (
              <Button onClick={() => setLabels(true)} sx={{ minHeight: 44 }}>
                Export label
              </Button>
            ) : null}
            {editing ? (
              <>
                <Button
                  disabled={draftBusy}
                  onClick={() => setEditing(false)}
                  sx={{ minHeight: 44 }}
                >
                  Cancel
                </Button>
                <Button
                  disabled={draftBusy}
                  variant="contained"
                  onClick={() => setEditing(false)}
                  sx={{ minHeight: 44 }}
                >
                  Done
                </Button>
              </>
            ) : null}
          </Stack>
        }
      />
      <Menu
        id="asset-actions-menu"
        anchorEl={menu}
        open={Boolean(menu)}
        onClose={() => setMenu(null)}
      >
        <MenuItem
          onClick={() => {
            setMenu(null);
            setEditing(true);
            setExpanded(true);
          }}
          sx={{ minHeight: 44 }}
        >
          Edit
        </MenuItem>
        <MenuItem
          onClick={() => {
            setMenu(null);
            setRepairMode("open");
          }}
          sx={{ minHeight: 44 }}
        >
          Open repair
        </MenuItem>
        {model.canContainAssets && asset.sealable ? (
          <MenuItem
            disabled={busy}
            onClick={() => {
              setMenu(null);
              void openSeal();
            }}
            sx={{ minHeight: 44 }}
          >
            Open seal
          </MenuItem>
        ) : null}
        <MenuItem
          onClick={() => {
            setMenu(null);
            setActionError(undefined);
            setArchiveConfirm(true);
          }}
          sx={{ minHeight: 44 }}
        >
          {asset.archived ? "Restore" : "Archive"}
        </MenuItem>
      </Menu>
      <Stack spacing={3}>
        {error ? (
          <Alert
            severity="error"
            action={
              <Button color="inherit" onClick={() => void load()}>
                Retry
              </Button>
            }
          >
            Could not refresh the asset: {error}
          </Alert>
        ) : null}
        {actionError && !archiveConfirm ? <Alert severity="error">{actionError}</Alert> : null}
        {asset.archived ? <Alert severity="info">This asset is archived.</Alert> : null}
        {asset.lifecycleState === "LOST" ? (
          <Alert severity="error">
            This asset is marked lost. An Owner or Deputy must restore it before normal use.
          </Alert>
        ) : null}
        {asset.metadataIncomplete ? (
          <Alert severity="warning">
            Required model-defined values are missing. Edit to complete this asset's metadata.
          </Alert>
        ) : null}
        <Box
          sx={{
            display: "grid",
            gridTemplateColumns: { xs: "minmax(0, 1fr)", md: "minmax(0, 1.1fr) minmax(0, 1fr)" },
            gap: 3,
            alignItems: "start",
          }}
        >
          <Paper variant="outlined" sx={{ minWidth: 0, overflow: "hidden" }}>
            <Stack
              direction="row"
              sx={{
                p: 2,
                bgcolor: "primary.main",
                color: "primary.contrastText",
                alignItems: "center",
                justifyContent: "space-between",
              }}
            >
              <Box>
                <Typography variant="body2">Public asset code</Typography>
                <Typography
                  component="div"
                  sx={{
                    fontFamily: "monospace",
                    fontSize: { xs: "1.8rem", sm: "2rem" },
                    fontWeight: 800,
                    letterSpacing: 3,
                    overflowWrap: "anywhere",
                  }}
                >
                  {asset.publicCode}
                </Typography>
              </Box>

              <Stack direction="row" sx={{ flexShrink: 0 }}>
                {" "}
                {canManage ? (
                  <IconButton
                    disabled={draftBusy || busy}
                    aria-label="Asset actions"
                    aria-controls={menu ? "asset-actions-menu" : undefined}
                    aria-haspopup="true"
                    aria-expanded={Boolean(menu)}
                    onClick={(event) => setMenu(event.currentTarget)}
                    sx={{ width: 44, height: 44, color: "inherit" }}
                  >
                    <MoreVertIcon />
                  </IconButton>
                ) : null}
                <IconButton
                  aria-label={expanded ? "Collapse asset details" : "Expand asset details"}
                  aria-expanded={expanded}
                  onClick={() => setExpanded((current) => !current)}
                  sx={{ color: "inherit", width: 44, height: 44 }}
                >
                  <ExpandMoreIcon sx={{ transform: expanded ? "rotate(180deg)" : undefined }} />
                </IconButton>
              </Stack>
            </Stack>
            <Collapse in={expanded}>
              {editing ? (
                <AssetDetailsEditor
                  asset={asset}
                  placement={placement}
                  containerCapable={model.canContainAssets}
                  onSaved={saved}
                  onBusyChange={setDraftBusy}
                />
              ) : (
                <Stack spacing={2} sx={{ p: 2 }}>
                  <Typography variant="body2" color="text.secondary">
                    {asset.assetModelName} / Unit {asset.unitNumber}
                  </Typography>
                  {asset.purchaseDate ? (
                    <Typography>
                      <Box component="span" sx={{ color: "text.secondary" }}>
                        Purchase date:{" "}
                      </Box>
                      {asset.purchaseDate}
                    </Typography>
                  ) : null}
                  <Box sx={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 2 }}>
                    <Box>
                      <Typography variant="body2" color="text.secondary">
                        Condition
                      </Typography>
                      <Typography sx={{ fontWeight: 600 }}>
                        {asset.condition === "GOOD" ? "Good" : "Damaged"}
                      </Typography>
                    </Box>
                    <Box>
                      <Typography variant="body2" color="text.secondary">
                        Lifecycle
                      </Typography>
                      <Typography sx={{ fontWeight: 600 }}>
                        {asset.lifecycleState.charAt(0) +
                          asset.lifecycleState.slice(1).toLowerCase()}
                      </Typography>
                    </Box>
                  </Box>
                  <Divider />
                  <Box>
                    <Typography variant="body2" color="text.secondary">
                      Direct location
                    </Typography>
                    <Typography>{locationName || "No direct location"}</Typography>
                  </Box>
                  <Box>
                    <Typography variant="body2" color="text.secondary">
                      Parent container
                    </Typography>
                    {parentName ? (
                      <Link
                        component={RouterLink}
                        to={`/inventory/assets/${placement.parentContainerAssetId}`}
                      >
                        {parentName}
                      </Link>
                    ) : (
                      <Typography>No parent container</Typography>
                    )}
                  </Box>
                  {model.canContainAssets ? (
                    <>
                      <Divider />
                      <Box>
                        <Typography variant="body2" color="text.secondary">
                          Seal
                        </Typography>
                        <Typography>
                          {!asset.sealable
                            ? "Not sealable"
                            : asset.sealState === "VERIFIED"
                              ? "Verified"
                              : asset.sealState === "APPLIED"
                                ? "Applied - verification required"
                                : asset.sealState === "INVALIDATED"
                                  ? "Invalidated - verification required"
                                  : "Open - verification required"}
                        </Typography>
                        {asset.sealVerifiedAt && asset.sealState === "VERIFIED" ? (
                          <Typography variant="body2" color="text.secondary">
                            Verified {new Date(asset.sealVerifiedAt).toLocaleString()}
                          </Typography>
                        ) : null}
                      </Box>
                    </>
                  ) : null}
                  {openRepairs.length ? (
                    <Alert severity="warning">
                      <Typography sx={{ fontWeight: 700 }}>In repair</Typography>
                      {openRepairs.map((repair) => (
                        <Typography key={repair.id} variant="body2">
                          {repair.referenceOrDescription}
                        </Typography>
                      ))}
                    </Alert>
                  ) : null}
                  {asset.values.length ? (
                    <>
                      <Divider />
                      {[...asset.values]
                        .sort((a, b) => a.displayOrder - b.displayOrder)
                        .map((value) => (
                          <Box key={value.fieldId}>
                            <Typography variant="body2" color="text.secondary">
                              {value.fieldName}
                            </Typography>
                            <Typography sx={{ overflowWrap: "anywhere" }}>
                              {value.stringValue ||
                                value.dateValue ||
                                value.optionValue ||
                                "Missing"}
                            </Typography>
                          </Box>
                        ))}
                    </>
                  ) : null}
                </Stack>
              )}
            </Collapse>
            {editing ? (
              <Box sx={{ px: 2, pb: 2 }}>
                <Button onClick={() => setRepairMode("manage")} sx={{ minHeight: 44 }}>
                  Repairs and history
                </Button>
              </Box>
            ) : history.length || repairs.length || model.canContainAssets ? (
              <Box sx={{ px: 2, pb: 2 }}>
                <Button onClick={() => setRepairMode("view")} sx={{ minHeight: 44 }}>
                  View history
                </Button>
              </Box>
            ) : null}
          </Paper>
          <Box sx={{ minWidth: 0 }}>
            <MediaPanel
              assetId={asset.id}
              fallbackAssetModelId={asset.assetModelId}
              containerCapable={model.canContainAssets}
              canManage={canManage && editing}
            />
          </Box>
        </Box>
        {model.canContainAssets ? (
          <AssetContentsCard
            assetId={asset.id}
            revision={revision}
            downloadAction={
              <Button
                onClick={() => void download()}
                disabled={downloadBusy}
                sx={{ minHeight: 44 }}
              >
                {downloadBusy ? "Preparing sheet." : "Download packing sheet"}
              </Button>
            }
            downloadError={downloadError}
            onDownloadRetry={() => void download()}
          />
        ) : null}
        {model.canContainAssets && editing ? (
          <PackingPanel
            containerAssetId={asset.id}
            canManage={canManage}
            showDownload={false}
            onContentsChanged={() => {
              void saved();
            }}
          />
        ) : null}
        {editing && history.length ? (
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="h3" sx={{ mb: 1 }}>
              Condition and lifecycle history
            </Typography>
            <Stack spacing={1}>
              {history.map((row) => (
                <Typography key={row.id} variant="body2">
                  {row.changeType === "CONDITION" ? "Condition" : "Lifecycle"}: {row.previousValue}
                  {" \u2192 "}
                  {row.newValue} / {new Date(row.changedAt).toLocaleString()}
                  {row.reason ? ` / ${row.reason}` : ""}
                </Typography>
              ))}
            </Stack>
          </Paper>
        ) : null}
      </Stack>
      <Dialog
        open={archiveConfirm}
        onClose={() => {
          if (!busy) setArchiveConfirm(false);
        }}
        fullWidth
        maxWidth="xs"
      >
        <DialogTitle>{asset.archived ? "Restore asset?" : "Archive asset?"}</DialogTitle>
        <DialogContent>
          <Stack spacing={2}>
            {actionError ? <Alert severity="error">{actionError}</Alert> : null}
            <Typography>
              {asset.archived
                ? "Restore this asset to ordinary inventory lists."
                : "Hide this asset from ordinary inventory lists. Its history remains available."}
            </Typography>
            <Typography sx={{ fontWeight: 700 }}>
              {asset.displayName} ({asset.publicCode})
            </Typography>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button disabled={busy} onClick={() => setArchiveConfirm(false)}>
            Cancel
          </Button>
          <Button
            disabled={busy}
            color={asset.archived ? "primary" : "warning"}
            variant="contained"
            onClick={() => void archive()}
          >
            {asset.archived ? "Restore asset" : "Archive asset"}
          </Button>
        </DialogActions>
      </Dialog>
      {repairMode ? (
        <AssetRepairDialog
          asset={asset}
          repairs={repairs}
          mode={repairMode}
          stateHistory={history}
          onSaved={saved}
          onClose={() => setRepairMode(undefined)}
        />
      ) : null}
      {labels ? (
        <AssetLabelExportDialog assetIds={[asset.id]} onClose={() => setLabels(false)} />
      ) : null}
    </>
  );
}
