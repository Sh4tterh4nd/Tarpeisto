import { useEffect, useEffectEvent, useRef, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import Accordion from "@mui/material/Accordion";
import AccordionSummary from "@mui/material/AccordionSummary";
import AccordionDetails from "@mui/material/AccordionDetails";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import {
  getAuditManualCandidates,
  type AuditManualCandidate,
  type ContainerAudit,
  type AuditExpectedRequirement,
} from "./auditApi";
import type { useAuditQueue } from "./useAuditQueue";
import { Link as RouterLink } from "react-router-dom";
import { useSession } from "../identity/useSession";
import { expectedLabel, scanLabel } from "./auditPresentation";
import { AuditEvidencePanel } from "./AuditEvidencePanel";

export function AuditDetails({
  audit,
  queue,
  onReport,
  onPresent,
  onQrMissing,
  readOnly = false,
}: {
  audit: ContainerAudit;
  queue: ReturnType<typeof useAuditQueue>;
  onReport: (assetId: string, label: string) => void;
  onPresent: (code: string) => void;
  onQrMissing: (assetId: string, label: string) => void;
  readOnly?: boolean;
}) {
  const { role, principal } = useSession();
  const [candidates, setCandidates] = useState<AuditManualCandidate[]>([]);
  const [cursor, setCursor] = useState<string>();
  const [candidateError, setCandidateError] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState("");
  const [loadedSearch, setLoadedSearch] = useState("");
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [reasons, setReasons] = useState<Record<string, string>>({});
  const alive = useRef(true);
  const request = useRef(0);
  const loadInitial = useEffectEvent(() => void load());
  useEffect(() => {
    alive.current = true;
    queueMicrotask(() => {
      if (alive.current) loadInitial();
    });
    return () => {
      alive.current = false;
    };
  }, []);
  async function load(more = false) {
    if (!audit.id) return;
    const epoch = ++request.current;
    setLoading(true);
    if (!more) {
      setCandidates([]);
      setCursor(undefined);
    }
    const result = await getAuditManualCandidates(audit.id, search, more ? cursor : undefined);
    if (!alive.current || epoch !== request.current) return;
    setLoading(false);
    if (result.kind === "ok") {
      setCandidates((old) => (more ? [...old, ...result.data.items] : result.data.items));
      setCursor(result.data.nextCursor);
      setLoadedSearch(search);
      setCandidateError(undefined);
    } else setCandidateError(result.error.problem?.detail ?? result.error.message);
  }
  const priority = (row: AuditExpectedRequirement) =>
    row.satisfied ? 2 : row.type === "MODEL_QUANTITY" && (row.matchedQuantity ?? 0) > 0 ? 1 : 0;
  const active = audit.scans.filter((scan) => !scan.undone);
  const unit = (assetId: string, code: string, label: string, enabled = true) => (
    <Stack
      key={assetId}
      spacing={0.5}
      sx={{
        py: 1,
        borderBottom: 1,
        borderColor: "divider",
        color: active.some((scan) => scan.assetId === assetId) ? "success.main" : "error.main",
      }}
    >
      <Typography>
        {label} - {code} /{" "}
        {active.some((scan) => scan.assetId === assetId) ? "Found" : "Not yet scanned"}
        {!enabled ? " / inactive" : ""}
      </Typography>
      {!readOnly ? (
        <Stack direction="row" sx={{ flexWrap: "wrap" }}>
          <Button disabled={!enabled} onClick={() => onPresent(code)}>
            Present
          </Button>
          <Button disabled={!enabled} onClick={() => onQrMissing(assetId, label)}>
            QR missing
          </Button>
          <Button onClick={() => onReport(assetId, label)}>Report</Button>
        </Stack>
      ) : null}
    </Stack>
  );
  function contextText(scan: ContainerAudit["scans"][number]) {
    try {
      const value = JSON.parse(scan.contextSnapshot) as {
        destinationContainerName?: string;
        destinationContainerCode?: string;
        suggestions?: { name?: string; code?: string }[];
      };
      return [
        value.destinationContainerName || value.destinationContainerCode
          ? `Required in ${[value.destinationContainerName, value.destinationContainerCode].filter(Boolean).join(" - ")}`
          : "",
        value.suggestions?.length
          ? `Could fill: ${value.suggestions.map((s) => [s.name, s.code].filter(Boolean).join(" - ")).join(", ")}`
          : "",
      ]
        .filter(Boolean)
        .join(" / ");
    } catch {
      return "";
    }
  }
  return (
    <Stack spacing={2}>
      {audit.blockingReasons.length ? (
        <Alert severity="warning">{audit.blockingReasons.join(" ")}</Alert>
      ) : null}
      <Typography>
        Exact items are matched before interchangeable model slots. Saved pending scans count only
        after server confirmation.
      </Typography>
      {[...audit.expectedRequirements]
        .sort((a, b) => priority(a) - priority(b))
        .map((row) => {
          const matches = active.filter((scan) =>
            row.type === "SPECIFIC_ASSET"
              ? scan.assetId === row.specificAssetId
              : scan.assetModelId === row.assetModelId && scan.outcome === "EXPECTED_MODEL",
          );
          const known = candidates.filter((candidate) =>
            row.type === "SPECIFIC_ASSET"
              ? candidate.id === row.specificAssetId
              : candidate.assetModelId === row.assetModelId,
          );
          const color = row.satisfied
            ? "success.main"
            : row.type === "MODEL_QUANTITY" && (row.matchedQuantity ?? 0) > 0
              ? "warning.main"
              : "error.main";
          const label =
            row.type === "CONSUMABLE_QUANTITY"
              ? `${row.requiredQuantity ?? ""} ${(() => {
                  try {
                    return String(
                      (JSON.parse(row.snapshot) as { stockUnitLabel?: string }).stockUnitLabel ??
                        "",
                    );
                  } catch {
                    return "";
                  }
                })()} required / ${row.satisfied ? "Required amount confirmed" : "Still expected"}`
              : row.matchedQuantity === undefined
                ? row.satisfied
                  ? "Found / confirmed"
                  : "Still expected"
                : `${row.matchedQuantity}/${row.requiredQuantity ?? 1} found`;
          return (
            <Accordion key={row.id} defaultExpanded={row.type !== "MODEL_QUANTITY"}>
              <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                <Box sx={{ color }}>
                  <Typography>{expectedLabel(row)}</Typography>
                  <Typography>{label}</Typography>
                </Box>
              </AccordionSummary>
              <AccordionDetails>
                {row.type === "CONSUMABLE_QUANTITY" && !readOnly ? (
                  <Stack spacing={1}>
                    <Button
                      onClick={() =>
                        void queue.enqueue({
                          kind: "consumable",
                          expectedId: row.id,
                          body: { operationId: crypto.randomUUID(), status: "CONFIRMED" },
                        })
                      }
                    >
                      Required amount present
                    </Button>
                    <TextField
                      label="Observed quantity"
                      type="number"
                      value={quantities[row.id] ?? ""}
                      onChange={(e) => setQuantities({ ...quantities, [row.id]: e.target.value })}
                    />
                    <TextField
                      label="Adjustment reason"
                      value={reasons[row.id] ?? ""}
                      onChange={(e) => setReasons({ ...reasons, [row.id]: e.target.value })}
                    />
                    <Button
                      onClick={() =>
                        void queue.enqueue({
                          kind: "consumable",
                          expectedId: row.id,
                          body: {
                            operationId: crypto.randomUUID(),
                            status: "MISSING_LOW",
                            reason: reasons[row.id],
                          },
                        })
                      }
                    >
                      Missing or low
                    </Button>
                    <Button
                      disabled={!quantities[row.id]}
                      onClick={() =>
                        void queue.enqueue({
                          kind: "consumable",
                          expectedId: row.id,
                          body: {
                            operationId: crypto.randomUUID(),
                            status: "OBSERVED",
                            observedQuantity: Number(quantities[row.id]),
                            reason: reasons[row.id],
                          },
                        })
                      }
                    >
                      Record observed quantity
                    </Button>
                  </Stack>
                ) : null}

                {known
                  .filter((c) => !matches.some((s) => s.assetId === c.id))
                  .map((c) => unit(c.id, c.publicCode, c.displayName, c.active))}
                {matches.map((scan) => unit(scan.assetId, scan.assetCode, scanLabel(scan)))}
                {row.type === "SPECIFIC_ASSET" &&
                row.specificAssetId &&
                !known.length &&
                !matches.length ? (
                  <Stack>
                    <Typography>{expectedLabel(row)}</Typography>
                    {!readOnly ? (
                      <>
                        <Button
                          onClick={() => {
                            try {
                              const s = JSON.parse(row.snapshot) as { assetCode?: string };
                              if (s.assetCode) onPresent(s.assetCode);
                            } catch {
                              /* manual entry remains */
                            }
                          }}
                        >
                          Present
                        </Button>
                        <Button
                          onClick={() => onQrMissing(row.specificAssetId!, expectedLabel(row))}
                        >
                          QR missing
                        </Button>
                      </>
                    ) : null}
                  </Stack>
                ) : null}
              </AccordionDetails>
            </Accordion>
          );
        })}
      <Box>
        <Typography variant="h6">Identifiable units</Typography>
        <Typography>
          Load only units associated with this audit. Offline, use known units above or manual code
          entry.
        </Typography>
        <TextField
          label="Find an audit unit"
          value={search}
          onChange={(e) => {
            setSearch(e.target.value);
            request.current++;
            setLoading(false);
            setCandidates([]);
            setCursor(undefined);
          }}
        />
        <Button disabled={loading} onClick={() => void load()}>
          Load audit units
        </Button>
        {candidateError ? (
          <Alert severity="warning">
            {candidateError}
            <Button onClick={() => void load()}>Retry units</Button>
          </Alert>
        ) : null}
        {loadedSearch === search
          ? candidates.map((c) => unit(c.id, c.publicCode, c.displayName, c.active))
          : null}
        {cursor && loadedSearch === search ? (
          <Button disabled={loading} onClick={() => void load(true)}>
            Show more units
          </Button>
        ) : null}
      </Box>
      <Typography variant="h6">All scans</Typography>
      {audit.scans.map((scan) => (
        <Stack key={scan.id}>
          <Typography>
            {scanLabel(scan)} - {scan.outcome.replaceAll("_", " ")}
            {scan.undone ? " - Undone" : ""}
          </Typography>
          {contextText(scan) ? (
            <Typography variant="caption">{contextText(scan)}</Typography>
          ) : null}
          {scan.recordedByDisplayName ? (
            <Typography>Recorded by {scan.recordedByDisplayName}</Typography>
          ) : null}
          {!readOnly && !scan.undone ? (
            <Stack direction="row">
              <Button onClick={() => onReport(scan.assetId, scanLabel(scan))}>Report</Button>
              <Button
                onClick={() =>
                  void queue.enqueue({
                    kind: "undo",
                    scanId: scan.id,
                    body: { operationId: crypto.randomUUID() },
                  })
                }
              >
                Undo
              </Button>
            </Stack>
          ) : null}
        </Stack>
      ))}
      <Typography variant="h6">Saved work awaiting synchronization</Typography>
      {queue.commands.map((row) => (
        <Stack key={row.operationId}>
          <Typography>
            {row.command.kind === "scan" || row.command.kind === "move"
              ? row.command.body.code
              : row.command.kind === "photo"
                ? row.command.fileName
                : row.command.kind}{" "}
            - {row.state}
            {row.error ? ` - ${row.error}` : ""}
          </Typography>
          <Stack direction="row">
            {row.state === "failed" || (row.state === "pending" && row.attempts === 0) ? (
              <Button onClick={() => void queue.cancel(row)}>Cancel saved operation</Button>
            ) : null}
            {row.state === "failed" ? (
              <Button onClick={() => void queue.retry(row)}>Retry saved operation</Button>
            ) : null}
            {row.state === "failed" &&
            row.error?.includes("another audit") &&
            (row.command.kind === "scan" || row.command.kind === "move") ? (
              <Button
                onClick={() => {
                  const code =
                    row.command.kind === "scan" || row.command.kind === "move"
                      ? row.command.body.code
                      : "";
                  void queue.cancel(row).then((ok) => {
                    if (ok)
                      void queue.enqueue({
                        kind: "move",
                        body: { operationId: crypto.randomUUID(), code },
                      });
                  });
                }}
              >
                Move scan here
              </Button>
            ) : null}
          </Stack>
        </Stack>
      ))}
      <Typography variant="h6">Findings for review</Typography>
      {audit.findings.map((finding) => (
        <Typography key={finding.id}>
          {finding.type.replaceAll("_", " ")}
          {finding.note ? ` - ${finding.note}` : ""}
          {finding.recordedByDisplayName ? ` / Recorded by ${finding.recordedByDisplayName}` : ""}
          {!principal?.temporaryAccess && (role === "OWNER" || role === "DEPUTY") ? (
            <Button component={RouterLink} to={`/review?finding=${finding.id}`}>
              Review finding
            </Button>
          ) : null}
        </Typography>
      ))}
      {audit.id ? (
        <AuditEvidencePanel auditId={audit.id} refreshKey={queue.commands.length} />
      ) : null}
    </Stack>
  );
}
