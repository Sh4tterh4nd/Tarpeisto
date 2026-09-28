import { useEffect, useRef, useState, type FormEvent } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import Divider from "@mui/material/Divider";
import FormControlLabel from "@mui/material/FormControlLabel";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { Link as RouterLink } from "react-router-dom";
import { PageHeading } from "@tarpeisto/shared-ui";
import { useParams } from "react-router-dom";
import { ScannerViewport } from "../scanner/ScannerViewport";
import { useSession } from "../identity/useSession";
import { webQrScannerCapability } from "../../platform/web/WebQrScannerCapability";
import {
  completeAudit,
  getAuditContainer,
  getAuditTask,
  moveAuditScanHere,
  observeAuditConsumable,
  recordAuditFinding,
  scanAudit,
  startAudit,
  undoAuditScan,
  type AuditResult,
  type AuditContainer,
  type ContainerAudit,
} from "./auditApi";

function expectedLabel(row: ContainerAudit["expectedRequirements"][number]) {
  try {
    const snapshot = JSON.parse(row.snapshot) as Record<string, unknown>;
    const modelName = typeof snapshot["modelName"] === "string" ? snapshot["modelName"] : undefined;
    const assetName = typeof snapshot["assetName"] === "string" ? snapshot["assetName"] : undefined;
    const assetCode = typeof snapshot["assetCode"] === "string" ? snapshot["assetCode"] : undefined;
    const unit =
      typeof snapshot["stockUnitLabel"] === "string" ? snapshot["stockUnitLabel"] : undefined;
    const identity = [assetName ?? modelName, assetCode].filter(Boolean).join(" - ");
    if (identity) return identity;
    if (unit && row.requiredQuantity) return `${row.requiredQuantity} ${unit}`;
  } catch {
    // Older frozen rows remain readable through the generic label below.
  }
  return row.type.replaceAll("_", " ");
}

function scanContext(scan: ContainerAudit["scans"][number]) {
  try {
    return JSON.parse(scan.contextSnapshot) as {
      modelName?: string;
      assetName?: string;
      destinationContainerName?: string;
      destinationContainerCode?: string;
      suggestions?: { name?: string; code?: string }[];
    };
  } catch {
    return {};
  }
}

export function AuditTaskPage() {
  const { role } = useSession();
  const { taskId = "" } = useParams();
  const [audit, setAudit] = useState<ContainerAudit>();
  const [container, setContainer] = useState<AuditContainer>();
  const [code, setCode] = useState("");
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [retryAction, setRetryAction] = useState<
    (() => Promise<AuditResult<ContainerAudit>>) | null
  >(null);
  const [confirmMissing, setConfirmMissing] = useState(false);
  const [sealConfirmed, setSealConfirmed] = useState(false);
  const [findingNote, setFindingNote] = useState("");
  const [observedQuantities, setObservedQuantities] = useState<Record<string, string>>({});
  const [observationReasons, setObservationReasons] = useState<Record<string, string>>({});
  useEffect(() => {
    let active = true;
    void getAuditTask(taskId).then(async (result) => {
      if (!active) return;
      if (result.kind === "error") {
        setError(result.error.problem?.detail ?? result.error.message);
        return;
      }
      setAudit(result.data);
      const containerResult = await getAuditContainer(result.data.containerAssetId);
      if (active && containerResult.kind === "ok") setContainer(containerResult.data);
    });
    return () => {
      active = false;
    };
  }, [taskId]);
  const apply = async (action: () => Promise<AuditResult<ContainerAudit>>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    const result = await action();
    busyRef.current = false;
    setBusy(false);
    if (result.kind === "ok") {
      setAudit(result.data);
      setError(undefined);
      setRetryAction(null);
    } else {
      setError(result.error.problem?.detail ?? result.error.message);
      setRetryAction(() => action);
    }
  };
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (audit?.id) {
      const operationId = crypto.randomUUID();
      await apply(() => scanAudit(audit.id!, code, operationId));
    } else await apply(() => startAudit(taskId, code));
  }
  if (!audit) return <Typography role="status">Loading audit.</Typography>;
  const started = Boolean(audit.id);
  const hasUnmet = audit.expectedRequirements.some((row) => !row.satisfied);
  const lastActiveScan = audit.scans.findLast((scan) => !scan.undone);
  return (
    <Stack spacing={2}>
      <PageHeading
        title={container ? `Container audit: ${container.displayName}` : "Container audit"}
        description={`${container ? `${container.publicCode}. ` : ""}Online-only: submissions are sent now. If one fails, retry it before completing.`}
      />
      {error ? (
        <Alert
          severity="error"
          action={
            retryAction || (audit.id && error.includes("another audit")) ? (
              <Stack direction="row" spacing={1}>
                {retryAction ? (
                  <Button color="inherit" disabled={busy} onClick={() => void apply(retryAction)}>
                    Retry submission
                  </Button>
                ) : null}
                {audit.id && error.includes("another audit") ? (
                  <Button
                    color="inherit"
                    disabled={busy}
                    onClick={() => {
                      const operationId = crypto.randomUUID();
                      void apply(() => moveAuditScanHere(audit.id!, code, operationId));
                    }}
                  >
                    Move scan here
                  </Button>
                ) : null}
              </Stack>
            ) : undefined
          }
        >
          {error}
        </Alert>
      ) : null}
      {audit.blockingReasons.length ? (
        <Alert severity="warning">{audit.blockingReasons.join(" ")}</Alert>
      ) : null}
      <Stack direction={{ xs: "column", md: "row" }} spacing={2} sx={{ alignItems: "start" }}>
        <Stack spacing={2} sx={{ flex: 1, width: "100%" }}>
          {started && audit.state === "IN_PROGRESS" ? (
            <ScannerViewport
              capability={webQrScannerCapability}
              onCode={(value) => {
                setCode(value);
                const operationId = crypto.randomUUID();
                void apply(() => scanAudit(audit.id!, value, operationId));
              }}
            />
          ) : null}
          {!started || audit.state === "IN_PROGRESS" ? (
            <Paper component="form" onSubmit={(event) => void submit(event)} sx={{ p: 2 }}>
              <Stack direction={{ xs: "column", sm: "row" }} spacing={1}>
                <TextField
                  required
                  fullWidth
                  label={started ? "Scan or enter item code" : "Scan assigned container to start"}
                  value={code}
                  onChange={(event) => setCode(event.target.value)}
                />
                <Button
                  type="submit"
                  variant="contained"
                  disabled={busy || (!started && audit.state !== "READY")}
                >
                  {started ? "Record scan" : "Start audit"}
                </Button>
              </Stack>
            </Paper>
          ) : null}
          {started ? (
            <Paper sx={{ p: 2 }}>
              <Typography variant="h6">Last scans</Typography>
              {audit.scans
                .slice(-4)
                .reverse()
                .map((scan) => {
                  const context = scanContext(scan);
                  const destination = [
                    context.destinationContainerName,
                    context.destinationContainerCode,
                  ]
                    .filter(Boolean)
                    .join(" - ");
                  const suggestions = context.suggestions
                    ?.map((suggestion) =>
                      [suggestion.name, suggestion.code].filter(Boolean).join(" - "),
                    )
                    .filter(Boolean)
                    .join(", ");
                  return (
                    <Stack
                      key={scan.id}
                      direction="row"
                      spacing={1}
                      sx={{ justifyContent: "space-between", py: 0.5 }}
                    >
                      <Box>
                        <Typography>
                          {[context.assetName ?? context.modelName, scan.assetCode]
                            .filter(Boolean)
                            .join(" - ")}{" "}
                          - {scan.outcome.replaceAll("_", " ")}
                        </Typography>
                        {destination ? (
                          <Typography variant="caption" sx={{ display: "block" }}>
                            Required in {destination}
                          </Typography>
                        ) : null}
                        {suggestions ? (
                          <Typography variant="caption" sx={{ display: "block" }}>
                            Could fill: {suggestions}
                          </Typography>
                        ) : null}
                      </Box>
                      {!scan.undone ? (
                        <Button
                          size="small"
                          disabled={busy}
                          onClick={() => {
                            const operationId = crypto.randomUUID();
                            void apply(() => undoAuditScan(audit.id!, scan.id, operationId));
                          }}
                        >
                          Undo
                        </Button>
                      ) : (
                        <Typography variant="caption">Undone</Typography>
                      )}
                    </Stack>
                  );
                })}
            </Paper>
          ) : null}
        </Stack>
        <Paper sx={{ p: 2, flex: 1, width: "100%" }}>
          <Typography variant="h6">Expected direct contents</Typography>
          <Typography color="text.secondary">
            Exact items are matched before interchangeable model slots.
          </Typography>
          <Divider sx={{ my: 1 }} />
          {audit.expectedRequirements.map((row) => (
            <Box key={row.id} sx={{ py: 1, borderBottom: 1, borderColor: "divider" }}>
              <Typography>
                {expectedLabel(row)} {row.requiredQuantity ? `× ${row.requiredQuantity}` : ""}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                {row.type.replaceAll("_", " ")}
              </Typography>
              <Typography variant="caption">
                {row.satisfied ? "Found / confirmed" : "Still expected"}
              </Typography>
              {started && audit.state === "IN_PROGRESS" && row.type === "CONSUMABLE_QUANTITY" ? (
                <Stack spacing={1} sx={{ mt: 1 }}>
                  <Stack direction={{ xs: "column", sm: "row" }} spacing={1}>
                    <Button
                      size="small"
                      disabled={busy}
                      onClick={() => {
                        const operationId = crypto.randomUUID();
                        void apply(() =>
                          observeAuditConsumable(
                            audit.id!,
                            row.id,
                            "CONFIRMED",
                            undefined,
                            undefined,
                            operationId,
                          ),
                        );
                      }}
                    >
                      Required amount present
                    </Button>
                    <Button
                      size="small"
                      disabled={busy}
                      onClick={() => {
                        const operationId = crypto.randomUUID();
                        void apply(() =>
                          observeAuditConsumable(
                            audit.id!,
                            row.id,
                            "MISSING_LOW",
                            undefined,
                            observationReasons[row.id],
                            operationId,
                          ),
                        );
                      }}
                    >
                      Missing or low
                    </Button>
                  </Stack>
                  <TextField
                    size="small"
                    type="number"
                    label="Observed quantity"
                    value={observedQuantities[row.id] ?? ""}
                    slotProps={{ htmlInput: { min: 0, step: 0.001 } }}
                    onChange={(event) =>
                      setObservedQuantities((current) => ({
                        ...current,
                        [row.id]: event.target.value,
                      }))
                    }
                    helperText="A balance change requires Owner or Deputy approval."
                  />
                  <TextField
                    size="small"
                    label="Adjustment reason"
                    value={observationReasons[row.id] ?? ""}
                    onChange={(event) =>
                      setObservationReasons((current) => ({
                        ...current,
                        [row.id]: event.target.value,
                      }))
                    }
                  />
                  <Button
                    size="small"
                    variant="outlined"
                    disabled={busy || !observedQuantities[row.id]}
                    onClick={() => {
                      const operationId = crypto.randomUUID();
                      void apply(() =>
                        observeAuditConsumable(
                          audit.id!,
                          row.id,
                          "OBSERVED",
                          Number(observedQuantities[row.id]),
                          observationReasons[row.id],
                          operationId,
                        ),
                      );
                    }}
                  >
                    Record observed quantity
                  </Button>
                </Stack>
              ) : null}
              {started &&
              audit.state === "IN_PROGRESS" &&
              row.type === "SPECIFIC_ASSET" &&
              row.specificAssetId ? (
                <Button
                  size="small"
                  disabled={busy}
                  onClick={() => {
                    const operationId = crypto.randomUUID();
                    void apply(() =>
                      recordAuditFinding(
                        audit.id!,
                        "UNREADABLE_LABEL",
                        row.specificAssetId,
                        "Present, but the label could not be read.",
                        operationId,
                      ),
                    );
                  }}
                >
                  Present - label unreadable
                </Button>
              ) : null}
            </Box>
          ))}
        </Paper>
      </Stack>
      {started && audit.state === "IN_PROGRESS" ? (
        <Paper sx={{ p: 2 }}>
          <Typography variant="h6">Finish audit</Typography>
          <Typography color="text.secondary">
            Close the container, then scan the same container code again. Missing items are recorded
            for review.
          </Typography>
          <TextField
            fullWidth
            sx={{ mt: 1 }}
            label="Damage note"
            value={findingNote}
            onChange={(event) => setFindingNote(event.target.value)}
            helperText="Optional. Add the important physical detail before marking damage."
          />
          {hasUnmet ? (
            <FormControlLabel
              sx={{ mt: 1 }}
              control={
                <Checkbox
                  checked={confirmMissing}
                  onChange={(event) => setConfirmMissing(event.target.checked)}
                />
              }
              label="I confirm that the remaining expected contents are missing."
            />
          ) : null}
          <FormControlLabel
            sx={{ mt: 1 }}
            control={
              <Checkbox
                checked={sealConfirmed}
                onChange={(event) => setSealConfirmed(event.target.checked)}
              />
            }
            label="I applied a seal if this container requires one."
          />
          <Stack direction={{ xs: "column", sm: "row" }} spacing={1} sx={{ mt: 1 }}>
            <Button
              variant="outlined"
              disabled={busy || !lastActiveScan}
              onClick={() => {
                if (!lastActiveScan) return;
                const operationId = crypto.randomUUID();
                void apply(() =>
                  recordAuditFinding(
                    audit.id!,
                    "DAMAGED",
                    lastActiveScan.assetId,
                    findingNote || undefined,
                    operationId,
                  ),
                );
              }}
            >
              Mark last scanned damaged
            </Button>
            <Button
              variant="contained"
              disabled={busy || (hasUnmet && !confirmMissing)}
              onClick={() => {
                const operationId = crypto.randomUUID();
                void apply(() =>
                  completeAudit(audit.id!, code, confirmMissing, sealConfirmed, operationId),
                );
              }}
            >
              Complete with this code
            </Button>
          </Stack>
        </Paper>
      ) : null}
      {audit.findings.length ? (
        <Paper sx={{ p: 2 }}>
          <Typography variant="h6">Findings for review</Typography>
          {audit.findings.map((finding) => (
            <Typography key={finding.id}>
              {finding.type.replaceAll("_", " ")}
              {finding.note ? ` - ${finding.note}` : ""}
              {role === "OWNER" || role === "DEPUTY" ? (
                <Button component={RouterLink} to={`/review?finding=${finding.id}`} size="small">
                  Review finding
                </Button>
              ) : null}
            </Typography>
          ))}
        </Paper>
      ) : null}
    </Stack>
  );
}
