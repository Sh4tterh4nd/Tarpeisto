import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import AddIcon from "@mui/icons-material/Add";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import {
  adjustStock,
  changeStock,
  errorMessage,
  getStockSummary,
  listAssetModels,
  listAssets,
  listStockBalances,
  listStockMovements,
  transferStock,
  type AssetRecord,
  type StockAction,
  type StockBalanceRecord,
  type StockMovementRecord,
  type StockSummaryRecord,
} from "./inventoryApi";

type StockDialogAction = StockAction | "adjust" | "transfer";

const ACTION_LABELS: Record<StockDialogAction, string> = {
  receive: "Receive stock",
  issue: "Issue for event",
  return: "Return from event",
  consume: "Record consumption",
  adjust: "Correct balance",
  transfer: "Transfer stock",
};

function isValidQuantity(value: string, allowNegative: boolean): boolean {
  const pattern = allowNegative ? /^-?\d+(?:\.\d{1,3})?$/ : /^\d+(?:\.\d{1,3})?$/;
  const amount = Number(value);
  return (
    pattern.test(value) && Number.isFinite(amount) && amount !== 0 && (allowNegative || amount > 0)
  );
}

interface StockActionDialogProps {
  assetModelId: string;
  action: StockDialogAction;
  containers: AssetRecord[];
  sourceContainerId?: string;
  unit: string;
  onClose: () => void;
  onChanged: () => Promise<void>;
}

function StockActionDialog({
  assetModelId,
  action,
  containers,
  sourceContainerId,
  unit,
  onClose,
  onChanged,
}: StockActionDialogProps) {
  const [containerId, setContainerId] = useState(sourceContainerId ?? containers[0]?.id ?? "");
  const [destinationId, setDestinationId] = useState(
    containers.find((container) => container.id !== sourceContainerId)?.id ?? "",
  );
  const [quantity, setQuantity] = useState("");
  const [note, setNote] = useState("");
  const [error, setError] = useState<string>();
  const [saving, setSaving] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (saving) return;
    const amount = Number(quantity);
    if (!isValidQuantity(quantity, action === "adjust")) {
      setError("Enter a non-zero amount with no more than three decimal places.");
      return;
    }
    setSaving(true);
    setError(undefined);
    const result =
      action === "adjust"
        ? await adjustStock(assetModelId, containerId, amount, note.trim())
        : action === "transfer"
          ? await transferStock(
              assetModelId,
              containerId,
              destinationId,
              amount,
              note.trim() || undefined,
            )
          : await changeStock(assetModelId, action, containerId, amount, note.trim() || undefined);
    setSaving(false);
    if (result.kind === "error") {
      setError(errorMessage(result.error));
      return;
    }
    await onChanged();
    onClose();
  }

  const quantityValid = isValidQuantity(quantity, action === "adjust");

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <Stack component="form" onSubmit={submit}>
        <DialogTitle>{ACTION_LABELS[action]}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {error ? <Alert severity="error">{error}</Alert> : null}
            <TextField
              select
              label={action === "transfer" ? "From container" : "Stock container"}
              value={containerId}
              onChange={(event) => {
                setContainerId(event.target.value);
                setError(undefined);
              }}
              required
            >
              {containers.map((container) => (
                <MenuItem key={container.id} value={container.id}>
                  {container.displayName}
                </MenuItem>
              ))}
            </TextField>
            {action === "transfer" ? (
              <TextField
                select
                label="To container"
                value={destinationId}
                onChange={(event) => {
                  setDestinationId(event.target.value);
                  setError(undefined);
                }}
                required
              >
                {containers
                  .filter((container) => container.id !== containerId)
                  .map((container) => (
                    <MenuItem key={container.id} value={container.id}>
                      {container.displayName}
                    </MenuItem>
                  ))}
              </TextField>
            ) : null}
            <TextField
              label={`${action === "adjust" ? "Signed adjustment" : "Quantity"} (${unit})`}
              type="number"
              value={quantity}
              onChange={(event) => {
                setQuantity(event.target.value);
                setError(undefined);
              }}
              slotProps={{
                htmlInput: { step: 0.001, min: action === "adjust" ? undefined : 0.001 },
              }}
              required
              autoFocus
            />
            <TextField
              label={action === "adjust" ? "Reason" : "Note (optional)"}
              value={note}
              onChange={(event) => {
                setNote(event.target.value);
                setError(undefined);
              }}
              required={action === "adjust"}
              multiline
              minRows={2}
            />
            {action === "adjust" ? (
              <Alert severity="warning">
                Corrections are permanent ledger entries. Use a signed amount and explain why the
                recorded balance is changing.
              </Alert>
            ) : null}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            type="submit"
            variant="contained"
            disabled={
              saving ||
              !containerId ||
              !quantityValid ||
              (action === "adjust" && !note.trim()) ||
              (action === "transfer" && (!destinationId || destinationId === containerId))
            }
          >
            {ACTION_LABELS[action]}
          </Button>
        </DialogActions>
      </Stack>
    </Dialog>
  );
}

function LedgerDialog({ balance, onClose }: { balance: StockBalanceRecord; onClose: () => void }) {
  const [movements, setMovements] = useState<StockMovementRecord[]>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    void (async () => {
      const result = await listStockMovements(balance.id);
      if (result.kind === "error") setError(errorMessage(result.error));
      else setMovements(result.data);
    })();
  }, [balance.id]);

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>Movement history · {balance.containerAssetDisplayName}</DialogTitle>
      <DialogContent>
        {error ? <Alert severity="error">{error}</Alert> : null}
        {!movements ? <CircularProgress size={24} /> : null}
        {movements?.length === 0 ? (
          <Typography color="text.secondary">No movements recorded.</Typography>
        ) : null}
        {movements && movements.length > 0 ? (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>When</TableCell>
                  <TableCell>Reason</TableCell>
                  <TableCell align="right">Change</TableCell>
                  <TableCell align="right">Balance</TableCell>
                  <TableCell>Note</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {movements.map((movement) => (
                  <TableRow key={movement.id}>
                    <TableCell>{new Date(movement.occurredAt).toLocaleString()}</TableCell>
                    <TableCell>{movement.reason.replaceAll("_", " ")}</TableCell>
                    <TableCell align="right" sx={{ fontWeight: 700 }}>
                      {movement.quantityDelta > 0 ? "+" : ""}
                      {movement.quantityDelta}
                    </TableCell>
                    <TableCell align="right">{movement.resultingQuantity}</TableCell>
                    <TableCell>{movement.note || "—"}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        ) : null}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}

export function ConsumableStockPanel({
  assetModelId,
  canManage,
}: {
  assetModelId: string;
  canManage: boolean;
}) {
  const [summary, setSummary] = useState<StockSummaryRecord>();
  const [balances, setBalances] = useState<StockBalanceRecord[]>();
  const [containers, setContainers] = useState<AssetRecord[]>([]);
  const [error, setError] = useState<string>();
  const [dialog, setDialog] = useState<{
    action: StockDialogAction;
    sourceContainerId?: string;
  }>();
  const [ledgerBalance, setLedgerBalance] = useState<StockBalanceRecord>();
  const loadRequest = useRef(0);

  const load = useCallback(async () => {
    const request = ++loadRequest.current;
    const [summaryResult, balanceResult, modelResult] = await Promise.all([
      getStockSummary(assetModelId),
      listStockBalances(assetModelId),
      listAssetModels(),
    ]);
    if (request !== loadRequest.current) return;
    if (summaryResult.kind === "error") {
      setSummary(undefined);
      setBalances(undefined);
      setError(errorMessage(summaryResult.error));
      return;
    }
    if (balanceResult.kind === "error") {
      setSummary(undefined);
      setBalances(undefined);
      setError(errorMessage(balanceResult.error));
      return;
    }
    if (modelResult.kind === "error") {
      setSummary(undefined);
      setBalances(undefined);
      setError(errorMessage(modelResult.error));
      return;
    }
    const containerModels = modelResult.data.filter(
      (model) =>
        model.trackingMode === "SERIALIZED_ASSET" && model.canContainAssets && !model.archived,
    );
    const assetResults = await Promise.all(
      containerModels.map((model) => listAssets(model.id, false)),
    );
    const containerAssets: AssetRecord[] = [];
    for (const result of assetResults) {
      if (request !== loadRequest.current) return;
      if (result.kind === "error") {
        setSummary(undefined);
        setBalances(undefined);
        setError(errorMessage(result.error));
        return;
      }
      containerAssets.push(...result.data.filter((asset) => asset.lifecycleState === "ACTIVE"));
    }
    setSummary(summaryResult.data);
    setBalances(balanceResult.data);
    setContainers(containerAssets);
    setError(undefined);
  }, [assetModelId]);

  useEffect(() => {
    void (async () => {
      await load();
    })();
  }, [load]);

  if (error) return <Alert severity="error">Could not load stock: {error}</Alert>;
  if (!summary || !balances) {
    return (
      <Stack direction="row" spacing={2} sx={{ alignItems: "center", py: 4 }}>
        <CircularProgress size={24} />
        <Typography role="status">Loading stock balances.</Typography>
      </Stack>
    );
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
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <Typography variant="h3">On hand: {summary.totalQuantity}</Typography>
              <Chip label={summary.stockUnitLabel} size="small" />
              {summary.lowStock ? <Chip label="Low stock" color="warning" size="small" /> : null}
            </Stack>
            <Typography variant="body2" color="text.secondary">
              {summary.lowStockThreshold === undefined || summary.lowStockThreshold === null
                ? "No low-stock threshold configured."
                : `Low-stock threshold: ${summary.lowStockThreshold} ${summary.stockUnitLabel}.`}
            </Typography>
          </Box>
          {canManage ? (
            <Button
              variant="contained"
              startIcon={<AddIcon />}
              onClick={() => setDialog({ action: "receive" })}
              disabled={containers.length === 0}
            >
              Receive stock
            </Button>
          ) : null}
        </Stack>
        {containers.length === 0 ? (
          <Alert severity="info" sx={{ mx: 2, mb: 2 }}>
            Stock needs a physical container. Create a unit of a container-capable serialized model
            before receiving this supply.
          </Alert>
        ) : null}
        {balances.length === 0 ? (
          <Typography color="text.secondary" sx={{ px: 2, pb: 3 }}>
            No stock has been received yet.
          </Typography>
        ) : (
          <TableContainer>
            <Table>
              <TableHead>
                <TableRow>
                  <TableCell>Container</TableCell>
                  <TableCell align="right">Quantity</TableCell>
                  <TableCell>Movement</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {balances.map((balance) => (
                  <TableRow key={balance.id} hover>
                    <TableCell>{balance.containerAssetDisplayName}</TableCell>
                    <TableCell align="right" sx={{ fontWeight: 700 }}>
                      {balance.quantity} {balance.stockUnitLabel}
                    </TableCell>
                    <TableCell>
                      <Stack direction="row" sx={{ gap: 0.5, flexWrap: "wrap" }}>
                        <Button size="small" onClick={() => setLedgerBalance(balance)}>
                          Ledger
                        </Button>
                        {canManage ? (
                          <>
                            <Button
                              size="small"
                              onClick={() =>
                                setDialog({
                                  action: "consume",
                                  sourceContainerId: balance.containerAssetId,
                                })
                              }
                            >
                              Consume
                            </Button>
                            <Button
                              size="small"
                              onClick={() =>
                                setDialog({
                                  action: "issue",
                                  sourceContainerId: balance.containerAssetId,
                                })
                              }
                            >
                              Issue
                            </Button>
                            <Button
                              size="small"
                              onClick={() =>
                                setDialog({
                                  action: "return",
                                  sourceContainerId: balance.containerAssetId,
                                })
                              }
                            >
                              Return
                            </Button>
                            <Button
                              size="small"
                              onClick={() =>
                                setDialog({
                                  action: "transfer",
                                  sourceContainerId: balance.containerAssetId,
                                })
                              }
                              disabled={containers.length < 2}
                            >
                              Transfer
                            </Button>
                            <Button
                              size="small"
                              color="warning"
                              onClick={() =>
                                setDialog({
                                  action: "adjust",
                                  sourceContainerId: balance.containerAssetId,
                                })
                              }
                            >
                              Correct
                            </Button>
                          </>
                        ) : null}
                      </Stack>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Paper>

      {dialog ? (
        <StockActionDialog
          assetModelId={assetModelId}
          action={dialog.action}
          sourceContainerId={dialog.sourceContainerId}
          containers={containers}
          unit={summary.stockUnitLabel}
          onClose={() => setDialog(undefined)}
          onChanged={load}
        />
      ) : null}
      {ledgerBalance ? (
        <LedgerDialog balance={ledgerBalance} onClose={() => setLedgerBalance(undefined)} />
      ) : null}
    </>
  );
}
