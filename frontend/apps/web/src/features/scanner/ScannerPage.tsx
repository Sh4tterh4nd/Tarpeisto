import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Divider from "@mui/material/Divider";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { PageHeading } from "@tarpeisto/shared-ui";
import { useNavigate } from "react-router-dom";
import type { QrScannerCapability } from "../../platform/capabilities/QrScannerCapability";
import { webQrScannerCapability } from "../../platform/web/WebQrScannerCapability";
import { validateAssetCode, type AssetCodeValidation } from "../asset-code/normalizeAssetCode";
import { ScannerViewport } from "./ScannerViewport";
import { lookupScannedAsset, scannerErrorMessage } from "./scannerApi";

const VALIDATION_MESSAGES: Record<Exclude<AssetCodeValidation, { valid: true }>["reason"], string> =
  {
    empty: "Enter the public asset code from the label.",
    "invalid-length": "That code is the wrong length. Public asset codes have six characters.",
    "invalid-symbol":
      "That code contains a character that never appears in a Tarpeisto asset code.",
    "checksum-mismatch":
      "That code does not check out. Re-read the label; this looks like a transcription error.",
  };

export interface ScannerPageProps {
  readonly capability?: QrScannerCapability;
}

/** Phase 6's continuous scanner, with a permanent keyboard-friendly manual fallback. */
export function ScannerPage({ capability = webQrScannerCapability }: ScannerPageProps) {
  const navigate = useNavigate();
  const [manualCode, setManualCode] = useState("");
  const [feedback, setFeedback] = useState<string>();
  const [loading, setLoading] = useState(false);
  const duplicateCodes = useRef(new Map<string, number>());
  const lookupSequence = useRef(0);

  useEffect(
    () => () => {
      lookupSequence.current += 1;
    },
    [],
  );

  const lookup = useCallback(
    async (rawCode: string, source: "camera" | "manual") => {
      const validation = validateAssetCode(rawCode);
      if (!validation.valid) {
        lookupSequence.current += 1;
        setLoading(false);
        setFeedback(VALIDATION_MESSAGES[validation.reason]);
        return;
      }

      if (source === "camera") {
        const now = Date.now();
        const previous = duplicateCodes.current.get(validation.normalized);
        duplicateCodes.current.set(validation.normalized, now);
        if (previous !== undefined && now - previous < 1500) {
          setFeedback(`Duplicate scan ignored: ${validation.normalized} was just checked.`);
          return;
        }
      }

      const request = ++lookupSequence.current;
      setLoading(true);
      setFeedback(undefined);
      const result = await lookupScannedAsset(validation.normalized);
      if (request !== lookupSequence.current) return;
      setLoading(false);
      if (result.kind === "error") {
        setFeedback(
          result.error.status === 404
            ? "That code is valid, but it is not assigned to an asset in this organization."
            : scannerErrorMessage(result.error),
        );
        return;
      }
      navigate(`/scan/assets/${result.data.id}`, { state: { scannedCode: validation.normalized } });
    },
    [navigate],
  );

  const onCameraCode = useCallback(
    (rawCode: string) => {
      void lookup(rawCode, "camera");
    },
    [lookup],
  );

  function submitManualCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void lookup(manualCode, "manual");
  }

  return (
    <Stack spacing={3}>
      <PageHeading
        title="Scan equipment"
        description="Check a label in, look up its current condition, and open its full inventory record."
      />
      <ScannerViewport capability={capability} onCode={onCameraCode} />

      <Paper
        component="section"
        aria-labelledby="manual-lookup-heading"
        variant="outlined"
        sx={{ borderLeft: 5, borderColor: "warning.main", maxWidth: 760 }}
      >
        <Stack component="form" spacing={2} onSubmit={submitManualCode} sx={{ p: 2.5 }}>
          <Box>
            <Typography id="manual-lookup-heading" variant="h3">
              Manual label entry
            </Typography>
            <Typography color="text.secondary">
              Use this whenever a camera is unavailable or a printed code cannot be read. Hyphens,
              spaces, and Crockford aliases are accepted.
            </Typography>
          </Box>
          <Divider />
          <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5} sx={{ alignItems: "start" }}>
            <TextField
              label="Public asset code"
              placeholder="7K3MXY"
              value={manualCode}
              onChange={(event) => {
                lookupSequence.current += 1;
                setLoading(false);
                setManualCode(event.target.value);
                setFeedback(undefined);
              }}
              slotProps={{ htmlInput: { autoComplete: "off", inputMode: "text" } }}
              sx={{ minWidth: { sm: 260 } }}
            />
            <Button type="submit" variant="contained" disabled={loading} sx={{ minHeight: 44 }}>
              {loading ? "Looking up" : "Open result"}
            </Button>
          </Stack>
          {feedback ? (
            <Alert severity={feedback.startsWith("Duplicate") ? "info" : "error"} role="alert">
              {feedback}
            </Alert>
          ) : null}
        </Stack>
      </Paper>
    </Stack>
  );
}
