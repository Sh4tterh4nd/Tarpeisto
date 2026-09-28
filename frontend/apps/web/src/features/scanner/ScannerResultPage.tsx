import { useCallback, useEffect, useRef, useState } from "react";
import ArrowBackIcon from "@mui/icons-material/ArrowBack";
import OpenInNewIcon from "@mui/icons-material/OpenInNew";
import RestoreIcon from "@mui/icons-material/Restore";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import Divider from "@mui/material/Divider";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { PageHeading } from "@tarpeisto/shared-ui";
import { Link as RouterLink, useLocation, useNavigate, useParams } from "react-router-dom";
import { useSession } from "../identity/useSession";
import {
  getScannedAsset,
  getScannedAssetModel,
  getScannedAssetPlacement,
  listScannedContainerContents,
  listScannedContainerStock,
  launchContainerAudit,
  restoreScannedAsset,
  scannerErrorMessage,
  type ScannerAsset,
  type ScannerPlacement,
  type ScannerStockBalance,
} from "./scannerApi";

interface ContainerSummary {
  readonly placement: ScannerPlacement | undefined;
  readonly contents: ScannerPlacement[] | undefined;
  readonly stock: ScannerStockBalance[] | undefined;
}

function lifecycleLabel(state: ScannerAsset["lifecycleState"]): string {
  return state.charAt(0) + state.slice(1).toLowerCase();
}

/** A focused post-scan result, deliberately distinct from the full edit-heavy asset page. */
export function ScannerResultPage() {
  const { assetId } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const { role } = useSession();
  const canRestore = role === "OWNER" || role === "DEPUTY";
  const canLaunchAudit = role === "OWNER" || role === "DEPUTY" || role === "OPERATOR_AUDITOR";
  const initialScannedCode =
    typeof location.state === "object" && location.state !== null && "scannedCode" in location.state
      ? (location.state as { scannedCode?: unknown }).scannedCode
      : undefined;
  const [asset, setAsset] = useState<ScannerAsset>();
  const [containerSummary, setContainerSummary] = useState<ContainerSummary>();
  const [error, setError] = useState<string>();
  const [restoring, setRestoring] = useState(false);
  const [auditPromptOpen, setAuditPromptOpen] = useState(false);
  const [auditPromptDismissed, setAuditPromptDismissed] = useState(false);
  const [launchingAudit, setLaunchingAudit] = useState(false);
  const [launchError, setLaunchError] = useState<string>();
  const request = useRef(0);
  const promptShown = useRef(false);
  const launchOperationId = useRef<string | undefined>(undefined);
  const [scanContext] = useState(() => ({
    assetId,
    code: typeof initialScannedCode === "string" ? initialScannedCode : undefined,
  }));
  const scannedCode = scanContext.assetId === assetId ? scanContext.code : undefined;
  const genuineScan = typeof scannedCode === "string";

  useEffect(() => {
    if (scannedCode) navigate(location.pathname, { replace: true });
  }, [location.pathname, navigate, scannedCode]);

  const load = useCallback(async () => {
    if (!assetId) return;
    const token = ++request.current;
    setError(undefined);
    setContainerSummary(undefined);
    const assetResult = await getScannedAsset(assetId);
    if (token !== request.current) return;
    if (assetResult.kind === "error") {
      setAsset(undefined);
      setError(scannerErrorMessage(assetResult.error));
      return;
    }
    setAsset(assetResult.data);

    const [modelResult, placementResult] = await Promise.all([
      getScannedAssetModel(assetResult.data.assetModelId),
      getScannedAssetPlacement(assetResult.data.id),
    ]);
    if (token !== request.current) return;
    if (modelResult.kind === "error") {
      setError(scannerErrorMessage(modelResult.error));
      return;
    }
    if (!modelResult.data.canContainAssets) {
      return;
    }

    if (
      genuineScan &&
      canLaunchAudit &&
      assetResult.data.lifecycleState === "ACTIVE" &&
      !assetResult.data.archived &&
      !promptShown.current
    ) {
      promptShown.current = true;
      setAuditPromptOpen(true);
    }

    const [contentsResult, stockResult] = await Promise.all([
      listScannedContainerContents(assetResult.data.id),
      listScannedContainerStock(assetResult.data.id),
    ]);
    if (token !== request.current) return;
    setContainerSummary({
      placement: placementResult.kind === "ok" ? placementResult.data : undefined,
      contents: contentsResult.kind === "ok" ? contentsResult.data : undefined,
      stock: stockResult.kind === "ok" ? stockResult.data : undefined,
    });
  }, [assetId, canLaunchAudit, genuineScan]);

  useEffect(() => {
    // Deferring the initial fetch avoids a cascading render while preserving
    // cancellation when the result route changes or unmounts.
    const scheduledLoad = window.setTimeout(() => void load(), 0);
    return () => {
      window.clearTimeout(scheduledLoad);
      request.current += 1;
    };
  }, [load]);

  async function restore() {
    if (!asset || restoring) return;
    setRestoring(true);
    const restored = await restoreScannedAsset(asset.id);
    setRestoring(false);
    if (restored.kind === "error") {
      setError(scannerErrorMessage(restored.error));
      return;
    }
    setAsset(restored.data);
  }

  async function startAudit() {
    if (!asset || !genuineScan || launchingAudit) return;
    launchOperationId.current ??= crypto.randomUUID();
    setLaunchingAudit(true);
    setLaunchError(undefined);
    const result = await launchContainerAudit(asset.id, scannedCode, launchOperationId.current);
    setLaunchingAudit(false);
    if (result.kind === "error") {
      setLaunchError(scannerErrorMessage(result.error));
      setAuditPromptOpen(false);
      setAuditPromptDismissed(true);
      return;
    }
    navigate(`/audits/tasks/${result.data.taskId}`);
  }

  if (error && !asset) {
    return (
      <Stack spacing={2}>
        <Button
          component={RouterLink}
          to="/scan"
          startIcon={<ArrowBackIcon />}
          sx={{ alignSelf: "start" }}
        >
          Back to scanner
        </Button>
        <Alert severity="error">Could not load this scan result: {error}</Alert>
      </Stack>
    );
  }
  if (!asset) {
    return (
      <Stack direction="row" spacing={2} sx={{ alignItems: "center", py: 5 }}>
        <CircularProgress size={24} />
        <Typography role="status">Loading scan result.</Typography>
      </Stack>
    );
  }

  return (
    <Stack spacing={3}>
      <Button
        component={RouterLink}
        to="/scan"
        startIcon={<ArrowBackIcon />}
        sx={{ alignSelf: "start" }}
      >
        Scan another label
      </Button>
      <PageHeading
        title={asset.displayName}
        description={`${asset.assetModelName} · ${asset.publicCode}`}
        actions={
          <Button
            component={RouterLink}
            to={`/inventory/assets/${asset.id}`}
            variant="outlined"
            endIcon={<OpenInNewIcon />}
          >
            Open full asset record
          </Button>
        }
      />

      {error ? <Alert severity="error">{error}</Alert> : null}
      {launchError ? (
        <Alert
          severity="error"
          action={
            <Button
              color="inherit"
              size="small"
              disabled={launchingAudit}
              onClick={() => void startAudit()}
            >
              Retry
            </Button>
          }
        >
          Could not start the container audit: {launchError}
        </Alert>
      ) : null}
      {asset.lifecycleState === "LOST" ? (
        <Alert
          severity="error"
          action={
            canRestore ? (
              <Button
                color="inherit"
                size="small"
                startIcon={<RestoreIcon />}
                onClick={() => void restore()}
                disabled={restoring}
              >
                {restoring ? "Restoring" : "Restore asset"}
              </Button>
            ) : undefined
          }
        >
          This asset is marked lost. Do not return it to normal use until its location and condition
          are confirmed.
        </Alert>
      ) : null}

      <Paper variant="outlined" sx={{ overflow: "hidden", maxWidth: 900 }}>
        <Box sx={{ bgcolor: "primary.main", color: "primary.contrastText", px: 2.5, py: 1.5 }}>
          <Typography variant="overline">Scanned equipment</Typography>
          <Typography
            sx={{ fontFamily: "monospace", fontWeight: 800, fontSize: "1.8rem", letterSpacing: 3 }}
          >
            {asset.publicCode}
          </Typography>
        </Box>
        <Stack
          direction={{ xs: "column", sm: "row" }}
          divider={<Divider flexItem orientation="vertical" />}
        >
          <Box sx={{ flex: 1, p: 2.5 }}>
            <Typography variant="body2" color="text.secondary">
              Model
            </Typography>
            <Typography sx={{ fontWeight: 700 }}>{asset.assetModelName}</Typography>
          </Box>
          <Box sx={{ flex: 1, p: 2.5 }}>
            <Typography variant="body2" color="text.secondary">
              Condition
            </Typography>
            <Typography sx={{ fontWeight: 700 }}>
              {asset.condition === "GOOD" ? "Good" : "Damaged"}
            </Typography>
          </Box>
          <Box sx={{ flex: 1, p: 2.5 }}>
            <Typography variant="body2" color="text.secondary">
              Lifecycle
            </Typography>
            <Typography sx={{ fontWeight: 700 }}>{lifecycleLabel(asset.lifecycleState)}</Typography>
          </Box>
        </Stack>
      </Paper>

      {containerSummary ? (
        <Paper
          component="section"
          aria-labelledby="container-summary-heading"
          variant="outlined"
          sx={{ maxWidth: 900, p: 2.5 }}
        >
          <Stack spacing={1.5}>
            <Box>
              <Typography id="container-summary-heading" variant="h3">
                Container summary
              </Typography>
              <Typography color="text.secondary">
                {containerSummary.placement?.effectivePathText ??
                  "Placement could not be loaded for this scan."}
              </Typography>
            </Box>
            <Divider />
            <Typography variant="h4">
              Direct contents
              {containerSummary.contents ? ` (${containerSummary.contents.length})` : ""}
            </Typography>
            {containerSummary.contents === undefined ? (
              <Alert severity="warning">
                Direct contents could not be loaded. Open the full asset record to retry.
              </Alert>
            ) : containerSummary.contents.length ? (
              <Stack component="ul" spacing={0.5} sx={{ m: 0, pl: 2.5 }}>
                {containerSummary.contents.slice(0, 6).map((item) => (
                  <Typography component="li" key={item.assetId}>
                    {item.effectivePath.at(-1) ?? "Unnamed asset"}
                  </Typography>
                ))}
                {containerSummary.contents.length > 6 ? (
                  <Typography component="li" color="text.secondary">
                    and {containerSummary.contents.length - 6} more
                  </Typography>
                ) : null}
              </Stack>
            ) : (
              <Typography color="text.secondary">
                No direct asset contents are currently recorded.
              </Typography>
            )}
            {containerSummary.stock === undefined ? (
              <Alert severity="warning">
                Consumable stock could not be loaded. Open the full asset record to retry.
              </Alert>
            ) : containerSummary.stock.length ? (
              <>
                <Typography variant="h4">Consumables carried here</Typography>
                <Stack component="ul" spacing={0.5} sx={{ m: 0, pl: 2.5 }}>
                  {containerSummary.stock.map((balance) => (
                    <Typography component="li" key={balance.id}>
                      {balance.assetModelName}: {balance.quantity} {balance.stockUnitLabel}
                    </Typography>
                  ))}
                </Stack>
              </>
            ) : null}
          </Stack>
        </Paper>
      ) : null}
      {containerSummary &&
      canLaunchAudit &&
      genuineScan &&
      asset.lifecycleState === "ACTIVE" &&
      !asset.archived &&
      auditPromptDismissed ? (
        <Button
          variant="outlined"
          sx={{ alignSelf: "start" }}
          onClick={() => setAuditPromptOpen(true)}
        >
          Start audit
        </Button>
      ) : null}
      <Dialog
        open={auditPromptOpen}
        onClose={() => {
          setAuditPromptOpen(false);
          setAuditPromptDismissed(true);
        }}
        aria-labelledby="start-container-audit-title"
      >
        <DialogTitle id="start-container-audit-title">Start a container audit?</DialogTitle>
        <DialogContent>
          <Typography>
            Start an online audit for this container and its nested containers. Child containers are
            checked first.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button
            onClick={() => {
              setAuditPromptOpen(false);
              setAuditPromptDismissed(true);
            }}
          >
            Not now
          </Button>
          <Button variant="contained" onClick={() => void startAudit()} disabled={launchingAudit}>
            {launchingAudit ? "Starting" : "Start audit"}
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
