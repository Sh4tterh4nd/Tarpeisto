import { useEffect, useEffectEvent, useRef, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import Dialog from "@mui/material/Dialog";
import DialogTitle from "@mui/material/DialogTitle";
import DialogContent from "@mui/material/DialogContent";
import DialogActions from "@mui/material/DialogActions";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useParams } from "react-router-dom";
import { ScannerViewport } from "../scanner/ScannerViewport";
import { webQrScannerCapability } from "../../platform/web/WebQrScannerCapability";
import { useSession } from "../identity/useSession";
import { normalizeAssetCode } from "../asset-code/normalizeAssetCode";
import {
  completeAudit,
  getAuditTask,
  startAudit,
  type ContainerAudit,
  type AuditFindingType,
} from "./auditApi";
import { useAuditQueue } from "./useAuditQueue";
import { AuditDetails } from "./AuditDetails";
import { missingLabel, scanLabel } from "./auditPresentation";
import { TemporaryInvitationsPanel } from "../temporary-access/TemporaryInvitationsPanel";
import { ArchiveButton } from "../archive/ArchiveButton";

export function AuditTaskPage() {
  const { taskId = "" } = useParams();
  const { principal } = useSession();
  const queue = useAuditQueue(taskId);
  return (
    <AuditCameraTask
      key={`${taskId}/${principal?.organizationId}/${principal?.userId}/${queue.audit?.id ?? "not-started"}`}
      taskId={taskId}
      queue={queue}
    />
  );
}
function AuditCameraTask({
  taskId,
  queue,
}: {
  taskId: string;
  queue: ReturnType<typeof useAuditQueue>;
}) {
  const { role, principal } = useSession();
  const { audit, container, error, setError, onAcknowledged, settledOperation } = queue;
  const [dialog, setDialog] = useState<
    "details" | "report" | "manual" | "finish" | "satisfied" | null
  >(null);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const alive = useRef(true);
  const [report, setReport] = useState<{ assetId?: string; label: string; type: AuditFindingType }>(
    { label: "Unknown item", type: "UNKNOWN_CODE" },
  );
  const [note, setNote] = useState("");
  const [photos, setPhotos] = useState<File[]>([]);
  const [reportOp, setReportOp] = useState(() => crypto.randomUUID());
  const [confirmMissing, setConfirmMissing] = useState(false);
  const [sealConfirmed, setSealConfirmed] = useState(false);
  const [closing, setClosing] = useState(false);
  const [closingCode, setClosingCode] = useState<string>();
  const [completionOp, setCompletionOp] = useState(() => crypto.randomUUID());
  const [feedback, setFeedback] = useState("");
  const newestOperation = useRef<string | undefined>(undefined);
  const [displayedUnit, setDisplayedUnit] = useState<{
    assetId?: string;
    label: string;
    type: AuditFindingType;
  }>();
  const [flash, setFlash] = useState(false);
  const owned = useRef(new Map<string, { code: string; pending: boolean }>());
  const [finishingComment, setFinishingComment] = useState(false);
  const [commentBarrier, setCommentBarrier] = useState<string>();
  const flashTimer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const [satisfiedShown, setSatisfiedShown] = useState(false);
  const progress = JSON.stringify([
    audit?.scans.map((s) => [s.id, s.outcome, s.undone]),
    audit?.expectedRequirements.map((r) => [r.id, r.satisfied, r.matchedQuantity]),
    audit?.findings.map((f) => f.id),
  ]);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      if (flashTimer.current) clearTimeout(flashTimer.current);
    };
  }, []);
  const acknowledge = useEffectEvent((operationId: string, ack?: ContainerAudit) => {
    if (!alive.current || !ack || ack.id !== audit?.id) return;
    const submitted = owned.current.get(operationId);
    if (!submitted) return;
    owned.current.delete(operationId);
    if (newestOperation.current !== operationId) return;
    const scan = ack.scans.find((scan) => scan.operationId === operationId && !scan.undone);
    if (scan) {
      setFeedback(`${scanLabel(scan)} - ${scan.outcome.replaceAll("_", " ")}`);
      setDisplayedUnit({ assetId: scan.assetId, label: scanLabel(scan), type: "DAMAGED" });
      if (scan.outcome === "EXPECTED_EXACT" || scan.outcome === "EXPECTED_MODEL") {
        setFlash(true);
        if (flashTimer.current) clearTimeout(flashTimer.current);
        flashTimer.current = setTimeout(() => {
          if (alive.current) setFlash(false);
        }, 450);
      }
    } else {
      const unknown = ack.findings.some(
        (finding) => finding.sourceOperationId === operationId && finding.type === "UNKNOWN_CODE",
      );
      const previous = unknown
        ? undefined
        : ack.scans.findLast((scan) => scan.assetCode === submitted.code && !scan.undone);
      setDisplayedUnit(
        previous
          ? { assetId: previous.assetId, label: scanLabel(previous), type: "DAMAGED" }
          : { label: submitted.code, type: "UNKNOWN_CODE" },
      );
      setFeedback(
        `${submitted.code} - ${ack.findings.some((finding) => finding.sourceOperationId === operationId) ? "Unknown code recorded for review" : "Duplicate / no new scan recorded"}`,
      );
    }
  });
  useEffect(
    () => onAcknowledged(({ operationId, audit: ack }) => acknowledge(operationId, ack)),
    [onAcknowledged],
  );
  const [submittedTick, setSubmittedTick] = useState(0);
  useEffect(() => {
    let active = true;
    for (const [operationId, submission] of owned.current) {
      if (!submission.pending) continue;
      void settledOperation(operationId)
        .then((ack) => {
          if (active && alive.current && ack) acknowledge(operationId, ack);
        })
        .catch(() => {
          /* Retry feedback on the next outbox snapshot. */
        });
    }
    return () => {
      active = false;
    };
  }, [settledOperation, queue.commands, audit, submittedTick]);
  const lastProgress = useRef(progress);
  useEffect(() => {
    if (lastProgress.current === progress) return;
    lastProgress.current = progress;
    setConfirmMissing(false);
    setSealConfirmed(false);
    setClosing(false);
    setClosingCode(undefined);
    setCompletionOp(crypto.randomUUID());
  }, [progress]);
  const unmet = audit?.expectedRequirements.filter((row) => !row.satisfied) ?? [];
  useEffect(() => {
    if (
      audit?.state === "IN_PROGRESS" &&
      audit.expectedRequirements.length > 0 &&
      !audit.expectedRequirements.some((row) => !row.satisfied) &&
      !satisfiedShown &&
      dialog === null &&
      !queue.completionBlocked
    ) {
      queueMicrotask(() => {
        if (alive.current) {
          setSatisfiedShown(true);
          setDialog("satisfied");
        }
      });
    }
  }, [audit, dialog, queue.completionBlocked, satisfiedShown]);
  useEffect(() => {
    if (audit?.expectedRequirements.some((row) => !row.satisfied) && satisfiedShown)
      queueMicrotask(() => {
        if (alive.current) setSatisfiedShown(false);
      });
  }, [audit?.expectedRequirements, satisfiedShown]);
  async function run(action: () => Promise<void>) {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try {
      await action();
    } catch (cause) {
      if (alive.current) setError(cause instanceof Error ? cause.message : "The operation failed.");
    } finally {
      busyRef.current = false;
      if (alive.current) setBusy(false);
    }
  }
  function openReport(
    assetId?: string,
    label = "Unknown item",
    type: AuditFindingType = "DAMAGED",
  ) {
    setCommentBarrier(undefined);
    setFinishingComment(false);
    setReport({ assetId, label, type });
    setNote("");
    setPhotos([]);
    setReportOp(crypto.randomUUID());
    setDialog("report");
  }
  async function receive(raw: string) {
    if (!audit || audit.state === "COMPLETED" || busyRef.current) return;
    const value = normalizeAssetCode(raw);
    setCommentBarrier(undefined);
    setCode(value);
    if (!audit.id) {
      await run(async () => {
        const result = await startAudit(taskId, value);
        if (!alive.current) return;
        if (result.kind === "ok") {
          await queue.accept(result.data);
          setError(undefined);
        } else setError(result.error.problem?.detail ?? result.error.message);
      });
      return;
    }
    if (container && value === normalizeAssetCode(container.publicCode)) {
      if (queue.completionBlocked) {
        setError("Synchronize all saved work before the closing scan.");
        return;
      }
      setClosingCode(value);
      setClosing(false);
      setDialog("finish");

      return;
    }
    if (closing) {
      setError("Scan the assigned container to finish, or continue auditing.");
      return;
    }
    setClosingCode(undefined);
    setConfirmMissing(false);
    setSealConfirmed(false);
    setCompletionOp(crypto.randomUUID());
    const op = crypto.randomUUID();
    newestOperation.current = op;
    setDisplayedUnit({ label: value, type: "UNKNOWN_CODE" });
    owned.current.set(op, { code: value, pending: false });
    const saved = await queue.enqueue({ kind: "scan", body: { operationId: op, code: value } });
    if (!alive.current) return;
    if (!saved) owned.current.delete(op);
    else {
      const submission = owned.current.get(op);
      if (submission) {
        submission.pending = true;
        setSubmittedTick((value) => value + 1);
        if (newestOperation.current === op) setFeedback(`${value} - Saved, pending confirmation`);
      }
    }
  }
  async function saveReport() {
    await run(async () => {
      const saved = await queue.findingWithPhotos(
        {
          kind: "finding",
          body: {
            operationId: reportOp,
            type: report.type,
            assetId: report.assetId,
            note: note || undefined,
          },
        },
        photos,
      );
      if (!alive.current || !saved) return;
      setPhotos([]);
      setNote("");
      setDialog(null);
      setCommentBarrier(finishingComment ? saved : undefined);
      setFeedback("Report saved, pending synchronization.");
      setClosing(false);
      setClosingCode(undefined);
      setConfirmMissing(false);
    });
  }
  useEffect(() => {
    if (!commentBarrier) return;
    const confirmed = audit?.findings.some((f) => f.sourceOperationId === commentBarrier);
    if (confirmed && !queue.completionBlocked && dialog === null && !busy)
      queueMicrotask(() => {
        if (alive.current) {
          setCommentBarrier(undefined);
          setClosing(true);
          setClosingCode(undefined);
          setDialog(null);
        }
      });
  }, [commentBarrier, audit?.findings, queue.completionBlocked, dialog, busy]);
  async function finish() {
    await run(async () => {
      if (!audit?.id || !closingCode) return;
      const result = await queue.complete((actor, signal) =>
        completeAudit(
          audit.id!,
          closingCode,
          confirmMissing,
          container?.sealable ? sealConfirmed : false,
          completionOp,
          actor,
          signal,
        ),
      );
      if (!alive.current) return;
      if (result.kind === "ok") {
        setError(undefined);
        setDialog(null);
        setClosing(false);
      } else setError(result.error.problem?.detail ?? result.error.message);
    });
  }
  if (!audit) return <Alert severity={error ? "error" : "info"}>{error ?? "Loading audit."}</Alert>;
  const last = audit.scans.findLast((scan) => !scan.undone);
  const inProgress = audit.state === "IN_PROGRESS";
  const canWrite = role !== "VIEWER";
  return (
    <Stack spacing={1} sx={{ flex: 1, minHeight: 0, overflow: "hidden", position: "relative" }}>
      <Stack
        direction="row"
        spacing={1}
        sx={{ alignItems: "center", justifyContent: "space-between", flexShrink: 0 }}
      >
        <Box sx={{ minWidth: 0 }}>
          <Typography
            component="h2"
            variant="h6"
            sx={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
          >
            {container ? `Container audit: ${container.displayName}` : "Container audit"}
          </Typography>
          <Typography variant="caption">
            {container?.publicCode} / {queue.syncStatus}
            {queue.commands.length ? ` / ${queue.commands.length} pending` : ""}
          </Typography>
        </Box>
      </Stack>
      {audit.blockingReasons.length ? (
        <Alert severity="warning" sx={{ flexShrink: 0, py: 0.5 }}>
          <Typography
            title={audit.blockingReasons.join(" ")}
            sx={{
              display: "-webkit-box",
              WebkitLineClamp: 2,
              WebkitBoxOrient: "vertical",
              overflow: "hidden",
              fontSize: ".85rem",
              lineHeight: 1.35,
            }}
          >
            {audit.blockingReasons.join(" ")}
          </Typography>
        </Alert>
      ) : null}
      {audit.state !== "COMPLETED" && canWrite ? (
        <ScannerViewport
          compact
          paused={dialog !== null || (!inProgress && audit.state !== "READY")}
          capability={webQrScannerCapability}
          onCode={(value) => void receive(value)}
          onManual={() => {
            setCommentBarrier(undefined);
            setDialog("manual");
          }}
        />
      ) : (
        <Box sx={{ flex: 1, minHeight: 0, overflowY: "auto" }}>
          <Typography>
            {audit.archived
              ? "Archived audit history"
              : audit.state === "COMPLETED"
                ? "Audit completed"
                : audit.id
                  ? "Audit details"
                  : "Audit not started"}
          </Typography>
          <AuditDetails
            audit={audit}
            queue={queue}
            readOnly
            onReport={() => {}}
            onPresent={() => {}}
            onQrMissing={() => {}}
          />
          {!principal?.temporaryAccess && (role === "OWNER" || role === "DEPUTY") && audit.id ? (
            <ArchiveButton
              kind="audit"
              id={audit.id}
              archived={audit.archived ?? false}
              version={audit.archiveVersion ?? 0}
              label="audit"
              onChanged={async () => {
                const result = await getAuditTask(taskId);
                if (!alive.current) return;
                if (result.kind === "ok") await queue.accept(result.data);
                else setError(result.error.problem?.detail ?? result.error.message);
              }}
            />
          ) : null}
        </Box>
      )}
      <Box sx={{ flexShrink: 0 }}>
        <Typography
          role="status"
          sx={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
        >
          {error ||
            (closing
              ? `Close the container, then scan ${container?.publicCode} again.`
              : feedback) ||
            (last
              ? `${scanLabel(last)} - ${last.outcome.replaceAll("_", " ")}`
              : "Scan an item to begin. Work is saved on this device before synchronization.")}
        </Typography>
        {(!displayedUnit || displayedUnit.assetId === last?.assetId) &&
        last?.recordedByDisplayName ? (
          <Typography
            variant="caption"
            noWrap
            sx={{ overflow: "hidden", textOverflow: "ellipsis" }}
          >
            Recorded by {last.recordedByDisplayName}
          </Typography>
        ) : null}
        <Stack direction="row" spacing={1} sx={{ "& .MuiButton-root": { minHeight: 44, flex: 1 } }}>
          <Button
            disabled={!inProgress || !canWrite || closing}
            onClick={() =>
              openReport(
                displayedUnit ? displayedUnit.assetId : last?.assetId,
                displayedUnit?.label ?? (last ? scanLabel(last) : "Unknown item"),
                displayedUnit?.type ?? (last ? "DAMAGED" : "UNKNOWN_CODE"),
              )
            }
          >
            Report
          </Button>
          <Button
            onClick={() => {
              setCommentBarrier(undefined);
              setDialog("details");
            }}
          >
            Details
          </Button>
          <Button
            variant="contained"
            disabled={!inProgress || busy || !canWrite}
            onClick={() => {
              setClosing(false);
              setClosingCode(undefined);
              setConfirmMissing(false);
              setSealConfirmed(false);
              setDialog("finish");
            }}
          >
            Finish
          </Button>
        </Stack>
      </Box>
      {flash ? (
        <Box
          data-testid="expected-scan-flash"
          aria-hidden
          sx={{
            position: "fixed",
            inset: 0,
            zIndex: (theme) => theme.zIndex.modal + 1,
            bgcolor: "success.main",
            opacity: 0.42,
            pointerEvents: "none",
            "@media (prefers-reduced-motion: reduce)": { opacity: 0.15 },
          }}
        />
      ) : null}
      <Dialog open={dialog === "details"} fullWidth maxWidth="md" onClose={() => setDialog(null)}>
        <DialogTitle>Audit details</DialogTitle>
        <DialogContent dividers>
          {error ? <Alert severity="error">{error}</Alert> : null}
          <AuditDetails
            key={audit.id ?? taskId}
            audit={audit}
            queue={queue}
            readOnly={!canWrite || !inProgress}
            onPresent={(value) => void receive(value)}
            onReport={(id, label) => openReport(id, label)}
            onQrMissing={(id, label) => openReport(id, label, "UNREADABLE_LABEL")}
          />
          {audit.batchId ? <TemporaryInvitationsPanel auditBatchId={audit.batchId} /> : null}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialog(null)}>Back to camera</Button>
        </DialogActions>
      </Dialog>
      <Dialog open={dialog === "manual"} fullWidth maxWidth="xs" onClose={() => setDialog(null)}>
        <DialogTitle>
          {audit.id ? "Enter item code" : "Scan assigned container to start"}
        </DialogTitle>
        <DialogContent>
          <TextField
            autoFocus
            fullWidth
            label={audit.id ? "Scan or enter item code" : "Scan assigned container to start"}
            onKeyDown={(event) => {
              if (event.key === "Enter" && code && !busy && (audit.id || audit.state === "READY")) {
                event.preventDefault();
                setDialog(null);
                void receive(code);
              }
            }}
            value={code}
            onChange={(event) => setCode(event.target.value)}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialog(null)}>Cancel</Button>
          <Button
            disabled={busy || !code || (!audit.id && audit.state !== "READY")}
            onClick={() => {
              setDialog(null);
              void receive(code);
            }}
          >
            {audit.id ? "Record scan" : "Start audit"}
          </Button>
        </DialogActions>
      </Dialog>
      <Dialog
        open={dialog === "report"}
        fullWidth
        maxWidth="sm"
        onClose={() => {
          if (!busy) setDialog(null);
        }}
      >
        <DialogTitle>Report: {report.label}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            <TextField
              select
              label="Finding"
              disabled={busy}
              value={report.type}
              onChange={(event) =>
                setReport({ ...report, type: event.target.value as AuditFindingType })
              }
            >
              <MenuItem value="DAMAGED">Damaged</MenuItem>
              <MenuItem value="UNREADABLE_LABEL" disabled={!report.assetId}>
                QR missing / unreadable
              </MenuItem>
              <MenuItem value="UNKNOWN_CODE">Unknown item / additional contents</MenuItem>
            </TextField>
            <TextField
              multiline
              label="Report note"
              disabled={busy}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              helperText="Optional. Describe damage or additional contents without a QR code."
            />
            <Button component="label">
              Choose optional evidence photographs
              <input
                type="file"
                hidden
                multiple
                accept="image/png,image/jpeg"
                onChange={(e) => setPhotos(Array.from(e.target.files ?? []))}
              />
            </Button>
            <Typography>{photos.length} photographs selected</Typography>
            {error ? <Alert severity="error">{error}</Alert> : null}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button disabled={busy} onClick={() => setDialog(null)}>
            Cancel
          </Button>
          <Button disabled={busy} onClick={() => void saveReport()}>
            Save report
          </Button>
        </DialogActions>
      </Dialog>
      <Dialog open={dialog === "satisfied"} fullWidth maxWidth="sm" onClose={() => setDialog(null)}>
        <DialogTitle>All required contents found</DialogTitle>
        <DialogContent>
          You can continue scanning additional units or note additional contents without a QR code.
        </DialogContent>
        <DialogActions>
          <Button
            onClick={() => {
              openReport(undefined, "Additional contents", "UNKNOWN_CODE");
              setFinishingComment(true);
            }}
          >
            Note additional contents
          </Button>
          <Button onClick={() => setDialog(null)}>Continue scanning</Button>
          <Button
            onClick={() => {
              setClosingCode(undefined);
              setDialog("finish");
            }}
          >
            Finish audit
          </Button>
        </DialogActions>
      </Dialog>
      <Dialog
        open={dialog === "finish"}
        fullWidth
        maxWidth="sm"
        onClose={() => {
          if (!busy) setDialog(null);
        }}
      >
        <DialogTitle>Finish audit</DialogTitle>
        <DialogContent>
          <Stack spacing={1}>
            {unmet.length ? (
              <>
                <Typography>Remaining expected contents:</Typography>
                {unmet.map((row) => (
                  <Typography key={row.id} color="error">
                    {missingLabel(row)}
                  </Typography>
                ))}
                <FormControlLabel
                  control={
                    <Checkbox
                      checked={confirmMissing}
                      onChange={(e) => setConfirmMissing(e.target.checked)}
                    />
                  }
                  label="I confirm that the remaining expected contents are missing."
                />
              </>
            ) : (
              <Typography>Required contents are found / confirmed.</Typography>
            )}
            <Typography>
              {closingCode
                ? "The assigned container was rescanned. Confirm it is closed."
                : "Review the contents, then close and rescan the assigned container."}
            </Typography>
            {closingCode && container?.sealable ? (
              <FormControlLabel
                control={
                  <Checkbox
                    checked={sealConfirmed}
                    onChange={(e) => setSealConfirmed(e.target.checked)}
                  />
                }
                label="I applied the required seal."
              />
            ) : null}
            {queue.completionBlocked ? (
              <Alert severity="warning">
                Synchronize all saved operations and photographs before finishing.
              </Alert>
            ) : null}
            {error ? <Alert severity="error">{error}</Alert> : null}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button disabled={busy} onClick={() => setDialog(null)}>
            Continue auditing
          </Button>
          {closingCode ? (
            <Button
              disabled={
                busy ||
                queue.completionBlocked ||
                (unmet.length > 0 && !confirmMissing) ||
                (Boolean(container?.sealable) && !sealConfirmed)
              }
              onClick={() => void finish()}
            >
              Complete audit
            </Button>
          ) : (
            <Button
              disabled={busy || queue.completionBlocked || (unmet.length > 0 && !confirmMissing)}
              onClick={() => {
                setClosing(true);
                setDialog(null);
              }}
            >
              Scan closed container
            </Button>
          )}
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
