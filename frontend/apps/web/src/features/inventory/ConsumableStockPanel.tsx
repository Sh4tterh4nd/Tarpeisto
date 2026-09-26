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
  listLocations,
  listStockBalances,
  listStockMovements,
  transferStock,
  type AssetRecord,
  type LocationRecord,
  type StockPlaceInput,
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
  places: StockPlaceOption[];
  sourcePlaceKey?: string;
  unit: string;
  onClose: () => void;
  onChanged: () => Promise<void>;
}

interface StockPlaceOption {
  key: string;
  label: string;
  place: StockPlaceInput;
}

function stockPlaceName(balance: StockBalanceRecord): string {
  return balance.locationDisplayName ?? balance.containerAssetDisplayName ?? "Unknown stock place";
}

function StockActionDialog({
  assetModelId,
  action,
  places,
  sourcePlaceKey,
  unit,
  onClose,
  onChanged,
}: StockActionDialogProps) {
  const [placeKey, setPlaceKey] = useState(sourcePlaceKey ?? places[0]?.key ?? "");
  const [destinationKey, setDestinationKey] = useState(
    places.find((place) => place.key !== sourcePlaceKey)?.key ?? "",
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
        ? await adjustStock(
            assetModelId,
            places.find((place) => place.key === placeKey)?.place ?? {},
            amount,
            note.trim(),
          )
        : action === "transfer"
          ? await transferStock(
              assetModelId,
              places.find((place) => place.key === placeKey)?.place ?? {},
              places.find((place) => place.key === destinationKey)?.place ?? {},
              amount,
              note.trim() || undefined,
            )
          : await changeStock(
              assetModelId,
              action,
              places.find((place) => place.key === placeKey)?.place ?? {},
              amount,
              note.trim() || undefined,
            );
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
              label={action === "transfer" ? "From stock place" : "Stock place"}
              value={placeKey}
              onChange={(event) => {
                setPlaceKey(event.target.value);
                setError(undefined);
              }}
              required
            >
              {places.map((place) => (
                <MenuItem key={place.key} value={place.key}>
                  {place.label}
                </MenuItem>
              ))}
            </TextField>
            {action === "transfer" ? (
              <TextField
                select
                label="To stock place"
                value={destinationKey}
                onChange={(event) => {
                  setDestinationKey(event.target.value);
                  setError(undefined);
                }}
                required
              >
                {places
                  .filter((place) => place.key !== placeKey)
                  .map((place) => (
                    <MenuItem key={place.key} value={place.key}>
                      {place.label}
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
              !placeKey ||
              !quantityValid ||
              (action === "adjust" && !note.trim()) ||
              (action === "transfer" && (!destinationKey || destinationKey === placeKey))
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
      <DialogTitle>Movement history · {stockPlaceName(balance)}</DialogTitle>
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
  const [locations, setLocations] = useState<LocationRecord[]>([]);
  const [error, setError] = useState<string>();
  const [dialog, setDialog] = useState<{
    action: StockDialogAction;
    sourcePlaceKey?: string;
  }>();
  const [ledgerBalance, setLedgerBalance] = useState<StockBalanceRecord>();
  const loadRequest = useRef(0);
  const stockPlaces: StockPlaceOption[] = [
    ...locations.map((location) => ({
      key: `location:${location.id}`,
      label: `Location: ${location.effectivePath}`,
      place: { locationId: location.id },
    })),
    ...containers.map((container) => ({
      key: `container:${container.id}`,
      label: `Container: ${container.displayName}`,
      place: { containerAssetId: container.id },
    })),
  ];

  const load = useCallback(async () => {
    const request = ++loadRequest.current;
    const [summaryResult, balanceResult, modelResult, locationsResult] = await Promise.all([
      getStockSummary(assetModelId),
      listStockBalances(assetModelId),
      listAssetModels(),
      listLocations(),
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
    if (locationsResult.kind === "error") {
      setError(errorMessage(locationsResult.error));
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
    setLocations(locationsResult.data.filter((location) => !location.archived));
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
                    <TableCell>{stockPlaceName(balance)}</TableCell>
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
                                  sourcePlaceKey: balance.locationId
                                    ? `location:${balance.locationId}`
                                    : `container:${balance.containerAssetId}`,
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
                                  sourcePlaceKey: balance.locationId
                                    ? `location:${balance.locationId}`
                                    : `container:${balance.containerAssetId}`,
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
                                  sourcePlaceKey: balance.locationId
                                    ? `location:${balance.locationId}`
                                    : `container:${balance.containerAssetId}`,
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
                                  sourcePlaceKey: balance.locationId
                                    ? `location:${balance.locationId}`
                                    : `container:${balance.containerAssetId}`,
                                })
                              }
                              disabled={stockPlaces.length < 2}
                            >
                              Transfer
                            </Button>
                            <Button
                              size="small"
                              color="warning"
                              onClick={() =>
                                setDialog({
                                  action: "adjust",
                                  sourcePlaceKey: balance.locationId
                                    ? `location:${balance.locationId}`
                                    : `container:${balance.containerAssetId}`,
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
          sourcePlaceKey={dialog.sourcePlaceKey}
          places={stockPlaces}
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
