import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Divider from "@mui/material/Divider";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import { useSearchParams } from "react-router-dom";
import { PageHeading } from "@tarpeisto/shared-ui";
import { useSession } from "../identity/useSession";
import { AuditEvidencePanel } from "../audits/AuditEvidencePanel";
import {
  type FindingReview,
  type ResolutionAction,
  getFinding,
  listFindings,
  resolveFinding,
  reviewError,
  reconcilePackingUntilComplete,
} from "./reviewApi";

const ACTIONS: Array<{ action: ResolutionAction; label: string; permanent?: boolean }> = [
  { action: "FOUND_AND_RETURNED", label: "Found and returned" },
  { action: "MOVE_TO_CORRECT_CONTAINER", label: "Move to correct container" },
  { action: "REASSIGN_CURRENT_CONTAINER", label: "Reassign current container" },
  { action: "MARK_DAMAGED", label: "Mark damaged" },
  { action: "CREATE_REPAIR", label: "Create repair" },
  { action: "REPLACE_LABEL", label: "Replace label" },
  { action: "MARK_LOST", label: "Mark lost", permanent: true },
  { action: "MARK_DESTROYED", label: "Mark destroyed", permanent: true },
  { action: "DISMISS", label: "Dismiss" },
];

export function ReviewPage() {
  const [searchParams] = useSearchParams();
  const { principal } = useSession();
  const identityKey = JSON.stringify([
    principal?.userId,
    principal?.organizationId,
    principal?.role,
  ]);
  const identity = useRef(identityKey);
  useLayoutEffect(() => {
    identity.current = identityKey;
  }, [identityKey]);
  const epoch = useRef(0);
  const invalidateRequests = useCallback(() => {
    ++epoch.current;
  }, []);
  const previousIdentity = useRef(identityKey);
  const canReconcile =
    !principal?.temporaryAccess && (principal?.role === "OWNER" || principal?.role === "DEPUTY");
  const [loadedIdentityKey, setLoadedIdentityKey] = useState<string>();
  const [reload, setReload] = useState(0);
  const [loading, setLoading] = useState(true);
  const [findings, setFindings] = useState<FindingReview[]>([]);
  const [selectedId, setSelectedId] = useState<string | undefined>(
    () => searchParams.get("finding") ?? undefined,
  );
  const [note, setNote] = useState("");
  const [targetContainerAssetId, setTargetContainerAssetId] = useState("");
  const [repairReference, setRepairReference] = useState("");
  const [action, setAction] = useState<ResolutionAction>();
  const [operationId, setOperationId] = useState<string>();
  const [operationPayload, setOperationPayload] = useState<string>();
  const [confirmPermanent, setConfirmPermanent] = useState(false);
  const [error, setError] = useState<string>();
  const selected = useMemo(
    () =>
      loadedIdentityKey === identityKey
        ? (findings.find((finding) => finding.id === selectedId) ?? findings[0])
        : undefined,
    [findings, selectedId, loadedIdentityKey, identityKey],
  );
  useEffect(() => {
    let active = true;
    const generation = ++epoch.current;
    const isCurrent = () =>
      active && generation === epoch.current && identity.current === identityKey;
    const identityChanged = previousIdentity.current !== identityKey;
    previousIdentity.current = identityKey;
    const timer = window.setTimeout(() => {
      setLoading(true);
      setError(undefined);
      setNote("");
      setTargetContainerAssetId("");
      setRepairReference("");
      setConfirmPermanent(false);
      if (identityChanged) setSelectedId(undefined);
      setFindings([]);
      setAction(undefined);
      setOperationId(undefined);
      setOperationPayload(undefined);
      void (async () => {
        if (canReconcile) {
          const reconciliation = await reconcilePackingUntilComplete(isCurrent);
          if (!isCurrent() || reconciliation.kind === "cancelled") return;
          if (reconciliation.kind === "error") {
            setError(`Packing review refresh failed: ${reviewError(reconciliation.error)}.`);
            setLoading(false);
            return;
          }
        }
        const result = await listFindings(true);
        if (!isCurrent()) return;
        setLoading(false);
        if (result.kind === "ok") {
          setFindings(result.data);
          setLoadedIdentityKey(identityKey);
          setSelectedId((current) =>
            result.data.some((item) => item.id === current) ? current : result.data[0]?.id,
          );
        } else setError(reviewError(result.error));
      })();
    });
    return () => {
      window.clearTimeout(timer);
      active = false;
      invalidateRequests();
    };
  }, [identityKey, canReconcile, reload, invalidateRequests]);
  useEffect(() => {
    if (!selectedId || loading || loadedIdentityKey !== identityKey) return;
    let active = true;
    const generation = epoch.current;
    void getFinding(selectedId).then((result) => {
      if (!active || generation !== epoch.current || identity.current !== identityKey) return;
      if (result.kind === "ok") {
        setFindings((current) =>
          current.map((item) => (item.id === result.data.id ? result.data : item)),
        );
      }
    });
    return () => {
      active = false;
    };
  }, [selectedId, loading, loadedIdentityKey, identityKey]);
  function selectAction(next: ResolutionAction) {
    setAction(next);
    setOperationId(undefined);
    setOperationPayload(undefined);
    setConfirmPermanent(false);
    setError(undefined);
  }
  async function decide() {
    if (!selected?.id) return;
    if (!action) return;
    if (action === "DISMISS" && !note.trim()) {
      setError("Dismissal requires a reason.");
      return;
    }
    if ((action === "MARK_LOST" || action === "MARK_DESTROYED") && !confirmPermanent) {
      setError("Confirm the permanent lifecycle change before saving it.");
      return;
    }
    if (
      (action === "MOVE_TO_CORRECT_CONTAINER" || action === "REASSIGN_CURRENT_CONTAINER") &&
      !targetContainerAssetId.trim()
    ) {
      setError("Enter the destination container ID.");
      return;
    }
    if (action === "CREATE_REPAIR" && !repairReference.trim()) {
      setError("Enter a repair reference or description.");
      return;
    }
    const payload = JSON.stringify({
      action,
      note: note.trim(),
      targetContainerAssetId: targetContainerAssetId.trim(),
      repairReference: repairReference.trim(),
    });
    const stableOperationId =
      payload === operationPayload && operationId ? operationId : crypto.randomUUID();
    setOperationId(stableOperationId);
    setOperationPayload(payload);
    const generation = epoch.current;
    const result = await resolveFinding(selected.id, {
      operationId: stableOperationId,
      action,
      note: note.trim() || undefined,
      targetContainerAssetId: targetContainerAssetId.trim() || undefined,
      repairReference: repairReference.trim() || undefined,
    });
    if (generation !== epoch.current || identity.current !== identityKey) return;
    if (result.kind === "error") {
      setError(reviewError(result.error));
      return;
    }
    setError(undefined);
    setFindings((current) => current.filter((finding) => finding.id !== selected.id));
    setSelectedId(undefined);
    setNote("");
    setAction(undefined);
    setOperationId(undefined);
    setOperationPayload(undefined);
    setConfirmPermanent(false);
    setTargetContainerAssetId("");
    setRepairReference("");
  }
  const currentFindings = loadedIdentityKey === identityKey ? findings : [];
  return (
    <Stack spacing={2}>
      <PageHeading
        title={
          loading
            ? "Refreshing findings"
            : error || loadedIdentityKey !== identityKey
              ? "Review findings"
              : `${currentFindings.length} findings need review`
        }
        actions={
          <Button
            disabled={loading}
            onClick={() => setReload((value) => value + 1)}
            sx={{ minHeight: 44 }}
          >
            Refresh review
          </Button>
        }
        description="Resolve completed-audit observations without changing the audit record."
      />
      {error ? <Alert severity="error">{error}</Alert> : null}
      <Box
        sx={{
          display: "grid",
          gridTemplateColumns: { xs: "1fr", md: "minmax(220px, .8fr) minmax(360px, 1.2fr)" },
          gap: 2,
        }}
      >
        <Paper component="nav" aria-label="Review queue" sx={{ p: 0 }}>
          {currentFindings.map((finding) => (
            <Box
              component="button"
              key={finding.id}
              onClick={() => {
                setSelectedId(finding.id);
                setAction(undefined);
                setOperationId(undefined);
                setOperationPayload(undefined);
                setNote("");
                setTargetContainerAssetId("");
                setRepairReference("");
                setConfirmPermanent(false);
              }}
              sx={{
                width: "100%",
                textAlign: "left",
                border: 0,
                bgcolor: selected?.id === finding.id ? "action.selected" : "background.paper",
                p: 2,
                minHeight: 64,
                cursor: "pointer",
              }}
            >
              <Typography sx={{ fontWeight: 700 }}>
                {String(finding.type).replaceAll("_", " ")}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {finding.note || "No observation note"}
              </Typography>
            </Box>
          ))}
          {!loading && !error && !currentFindings.length ? (
            <Typography sx={{ p: 2 }}>No unresolved findings.</Typography>
          ) : null}
        </Paper>
        <Paper sx={{ p: 2 }}>
          {selected ? (
            <Stack spacing={2}>
              <Typography variant="h6">{String(selected.type).replaceAll("_", " ")}</Typography>
              <Typography>{selected.note || "No note was recorded."}</Typography>
              <Divider />
              <Typography variant="body2" color="text.secondary">
                Audit {selected.auditId}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Audit task: {selected.auditTaskId || "Unavailable"}
                <br />
                Container: {selected.containerAssetId || "Unavailable"}
              </Typography>
              <AuditEvidencePanel key={selected.id} findingId={selected.id} />
              {selected.resolved ? (
                <Alert severity="success">
                  Resolved as {String(selected.resolutionAction).replaceAll("_", " ")}{" "}
                  {selected.resolvedAt
                    ? `on ${new Date(selected.resolvedAt).toLocaleString()}`
                    : ""}
                  .
                </Alert>
              ) : null}
              <TextField
                label="Decision note"
                value={note}
                onChange={(event) => setNote(event.target.value)}
                multiline
                minRows={2}
                helperText="A reason is required to dismiss a finding."
              />
              {action === "MOVE_TO_CORRECT_CONTAINER" || action === "REASSIGN_CURRENT_CONTAINER" ? (
                <TextField
                  required
                  label="Destination container ID"
                  value={targetContainerAssetId}
                  onChange={(event) => setTargetContainerAssetId(event.target.value)}
                  helperText="Use the ID of the container that should contain this asset."
                />
              ) : null}
              {action === "CREATE_REPAIR" ? (
                <TextField
                  required
                  label="Repair reference or description"
                  value={repairReference}
                  onChange={(event) => setRepairReference(event.target.value)}
                />
              ) : null}
              {action === "MARK_LOST" || action === "MARK_DESTROYED" ? (
                <FormControlLabel
                  control={
                    <Checkbox
                      checked={confirmPermanent}
                      onChange={(event) => setConfirmPermanent(event.target.checked)}
                    />
                  }
                  label="I understand this permanently changes the asset lifecycle and formally accounts for open custody."
                />
              ) : null}
              <Divider />
              <Typography sx={{ fontWeight: 700 }}>Decision rail</Typography>
              <Stack direction={{ xs: "column", sm: "row" }} sx={{ flexWrap: "wrap", gap: 1 }}>
                {ACTIONS.filter((item) =>
                  (selected.applicableActions ?? []).includes(item.action),
                ).map(({ action: candidate, label, permanent }) => (
                  <Button
                    key={candidate}
                    variant={action === candidate ? "contained" : "outlined"}
                    color={permanent ? "error" : "primary"}
                    onClick={() => selectAction(candidate)}
                  >
                    {permanent ? `${label} - permanent` : label}
                  </Button>
                ))}
              </Stack>
              <Button variant="contained" disabled={!action} onClick={() => void decide()}>
                {action ? `Save ${action.replaceAll("_", " ").toLowerCase()}` : "Choose a decision"}
              </Button>
              {operationId ? (
                <Typography variant="caption">
                  If this request fails, use Save again to retry the same operation safely.
                </Typography>
              ) : null}
            </Stack>
          ) : (
            <Typography>Select a finding to review.</Typography>
          )}
        </Paper>
      </Box>
    </Stack>
  );
}
