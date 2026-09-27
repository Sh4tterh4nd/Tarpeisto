import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { apiClient } from "@tarpeisto/api-client";
import type { components } from "@tarpeisto/api-client";

type Repair = Required<components["schemas"]["RepairResponse"]>;
type SealHistory = Required<components["schemas"]["SealHistoryView"]>;

export function AssetReviewPanel({
  assetId,
  canManage,
  containerCapable,
  lifecycleState,
  sealable,
  sealState,
  sealVerifiedAt,
  lastVerifiedAt,
  replacesAssetId,
}: {
  assetId: string;
  canManage: boolean;
  containerCapable: boolean;
  lifecycleState: string;
  sealable: boolean;
  sealState: string;
  sealVerifiedAt?: string;
  lastVerifiedAt?: string;
  replacesAssetId?: string;
}) {
  const [repairs, setRepairs] = useState<Repair[]>([]);
  const [history, setHistory] = useState<SealHistory[]>([]);
  const [reference, setReference] = useState("");
  const [breakNote, setBreakNote] = useState("");
  const [replacementName, setReplacementName] = useState("");
  const [message, setMessage] = useState<string>();
  const load = async () => {
    const [repairResult, historyResult] = await Promise.all([
      apiClient.GET("/api/v1/assets/{assetId}/repairs", { params: { path: { assetId } } }),
      apiClient.GET("/api/v1/assets/{assetId}/seal/history", { params: { path: { assetId } } }),
    ]);
    if (repairResult.data) setRepairs(repairResult.data as Repair[]);
    if (historyResult.data) setHistory(historyResult.data as SealHistory[]);
  };
  useEffect(() => {
    let active = true;
    void Promise.all([
      apiClient.GET("/api/v1/assets/{assetId}/repairs", { params: { path: { assetId } } }),
      apiClient.GET("/api/v1/assets/{assetId}/seal/history", { params: { path: { assetId } } }),
    ]).then(([repairResult, historyResult]) => {
      if (!active) return;
      if (repairResult.data) setRepairs(repairResult.data as Repair[]);
      if (historyResult.data) setHistory(historyResult.data as SealHistory[]);
    });
    return () => {
      active = false;
    };
  }, [assetId]);
  async function openRepair() {
    if (!reference.trim()) {
      setMessage("Enter a repair reference or description.");
      return;
    }
    const result = await apiClient.POST("/api/v1/assets/{assetId}/repairs", {
      params: { path: { assetId } },
      body: { referenceOrDescription: reference.trim() },
    });
    setMessage(
      result.error ? "Repair could not be opened." : "Repair opened; this asset is unavailable.",
    );
    if (!result.error) {
      setReference("");
      await load();
    }
  }
  async function closeRepair(repairId: string, resultingCondition: "GOOD" | "DAMAGED") {
    const result = await apiClient.POST("/api/v1/repairs/{repairId}/close", {
      params: { path: { repairId } },
      body: { resultingCondition },
    });
    setMessage(
      result.error
        ? "Repair could not be closed."
        : "Repair closed and the resulting condition was recorded.",
    );
    if (!result.error) await load();
  }
  async function setSealable(sealable: boolean) {
    const result = await apiClient.PUT("/api/v1/assets/{assetId}/sealable", {
      params: { path: { assetId } },
      body: { sealable },
    });
    setMessage(
      result.error
        ? "Seal setting could not be saved."
        : sealable
          ? "Container now requires seal confirmation."
          : "Container no longer requires a seal.",
    );
  }
  async function breakSeal() {
    const result = await apiClient.POST("/api/v1/assets/{assetId}/seal/break", {
      params: { path: { assetId } },
      body: { note: breakNote.trim() || undefined },
    });
    setMessage(
      result.error ? "Seal could not be broken." : "Seal broken; a fresh audit is required.",
    );
    if (!result.error) {
      setBreakNote("");
      await load();
    }
  }
  async function createReplacement() {
    const result = await apiClient.POST("/api/v1/assets/{assetId}/replacement", {
      params: { path: { assetId } },
      body: { individualName: replacementName.trim() || undefined, values: [] },
    });
    setMessage(
      result.error
        ? "Replacement could not be created. Complete any required model metadata first."
        : `Replacement created with code ${result.data?.publicCode ?? ""}.`,
    );
    if (!result.error) setReplacementName("");
  }
  return (
    <Paper sx={{ p: 2 }}>
      <Stack spacing={1.5}>
        <Typography variant="h6">Repair and seal</Typography>
        <Typography variant="body2">
          {sealable
            ? `Seal state: ${sealState.replaceAll("_", " ")}${sealVerifiedAt ? ` (verified ${new Date(sealVerifiedAt).toLocaleString()})` : ""}`
            : "This container is not sealable."}
        </Typography>
        {lastVerifiedAt ? (
          <Typography variant="body2">
            Last verified: {new Date(lastVerifiedAt).toLocaleString()}
          </Typography>
        ) : null}
        {replacesAssetId ? (
          <Typography variant="body2">
            Replacement lineage: replaces asset {replacesAssetId}
          </Typography>
        ) : null}
        {message ? <Alert severity="info">{message}</Alert> : null}
        <Typography variant="body2">
          {repairs.some((repair) => !repair.closedAt)
            ? "This asset is in repair and cannot be reserved or checked out."
            : "No open repair."}
        </Typography>
        {canManage ? (
          <Stack direction={{ xs: "column", sm: "row" }} spacing={1}>
            <TextField
              size="small"
              label="Repair reference or description"
              value={reference}
              onChange={(event) => setReference(event.target.value)}
            />
            <Button variant="outlined" onClick={() => void openRepair()}>
              Open repair
            </Button>
          </Stack>
        ) : null}
        {repairs.map((repair) => (
          <Stack key={repair.id} direction={{ xs: "column", sm: "row" }} spacing={1}>
            <Typography variant="body2">
              {repair.referenceOrDescription} —{" "}
              {repair.closedAt ? `closed (${repair.resultingCondition})` : "open"}
            </Typography>
            {canManage && !repair.closedAt ? (
              <TextField
                select
                size="small"
                label="Close repair as"
                defaultValue=""
                onChange={(event) => {
                  if (event.target.value)
                    void closeRepair(repair.id, event.target.value as "GOOD" | "DAMAGED");
                }}
              >
                <MenuItem value="">Choose result</MenuItem>
                <MenuItem value="GOOD">Good</MenuItem>
                <MenuItem value="DAMAGED">Damaged</MenuItem>
              </TextField>
            ) : null}
          </Stack>
        ))}
        {containerCapable && canManage ? (
          <Stack spacing={1}>
            <Stack direction={{ xs: "column", sm: "row" }} spacing={1}>
              <Button variant="outlined" onClick={() => void setSealable(true)}>
                Require seal
              </Button>
              <Button variant="outlined" onClick={() => void setSealable(false)}>
                Disable seal
              </Button>
            </Stack>
            <TextField
              size="small"
              label="Why break/open the seal?"
              value={breakNote}
              onChange={(event) => setBreakNote(event.target.value)}
            />
            <Button color="warning" onClick={() => void breakSeal()}>
              Break/open seal
            </Button>
          </Stack>
        ) : null}
        {history.length ? (
          <Typography variant="body2">
            Seal history:{" "}
            {history
              .map((item) => `${item.action} ${new Date(item.occurredAt).toLocaleString()}`)
              .join("; ")}
          </Typography>
        ) : null}
        {(lifecycleState === "LOST" || lifecycleState === "DESTROYED") && canManage ? (
          <Stack spacing={1}>
            <Alert severity="warning">
              A replacement is a new identity and code; this historical asset stays{" "}
              {lifecycleState.toLowerCase()}.
            </Alert>
            <TextField
              size="small"
              label="Replacement individual name"
              value={replacementName}
              onChange={(event) => setReplacementName(event.target.value)}
            />
            <Button variant="contained" onClick={() => void createReplacement()}>
              Create replacement
            </Button>
          </Stack>
        ) : null}
      </Stack>
    </Paper>
  );
}
