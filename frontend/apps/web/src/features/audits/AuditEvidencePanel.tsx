import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { listAuditEvidence, listFindingEvidence, type AuditEvidence } from "./auditEvidenceApi";

export function AuditEvidencePanel({
  auditId,
  findingId,
  refreshKey = 0,
}: {
  auditId?: string;
  findingId?: string;
  refreshKey?: number;
}) {
  const [evidence, setEvidence] = useState<AuditEvidence[]>([]);
  const [error, setError] = useState<string>();
  useEffect(() => {
    let active = true;
    const request = findingId
      ? listFindingEvidence(findingId)
      : auditId
        ? listAuditEvidence(auditId)
        : undefined;
    void request?.then((result) => {
      if (!active) return;
      if (result.kind === "ok") {
        setEvidence(result.data);
        setError(undefined);
      } else setError(result.error.problem?.detail ?? result.error.message);
    });
    return () => {
      active = false;
    };
  }, [auditId, findingId, refreshKey]);
  return (
    <Paper sx={{ p: 2 }}>
      <Typography variant="h6">Evidence photographs</Typography>
      {error ? <Alert severity="warning">Photographs could not be loaded: {error}</Alert> : null}
      {!evidence.length && !error ? <Typography>No evidence photographs.</Typography> : null}
      <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap" }}>
        {evidence.map((photo) => (
          <Box
            key={photo.id}
            component="a"
            href={photo.imageUrl}
            target="_blank"
            rel="noreferrer"
            aria-label="Open evidence photograph"
          >
            <Box
              component="img"
              src={photo.thumbnailUrl}
              alt="Audit finding evidence"
              sx={{ maxWidth: 240, maxHeight: 180, objectFit: "contain" }}
            />
          </Box>
        ))}
      </Stack>
    </Paper>
  );
}
