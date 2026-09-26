import { useRef, useState, type FormEvent } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { Link as RouterLink } from "react-router-dom";
import { PageHeading } from "@bigcontainers/shared-ui";
import { errorMessage, findAssetByCode, type AssetRecord } from "../inventory/inventoryApi";
import { validateAssetCode, type AssetCodeValidation } from "./normalizeAssetCode";

const REASON_MESSAGES: Record<Exclude<AssetCodeValidation, { valid: true }>["reason"], string> = {
  empty: "Enter a public asset code.",
  "invalid-length": "That code is the wrong length. A public asset code has six characters.",
  "invalid-symbol": "That code contains a character that never appears in an asset code.",
  "checksum-mismatch":
    "That code does not check out. Re-read the label carefully; this looks like a transcription error rather than an unknown code.",
};

/**
 * Manual public-code entry, always available alongside camera scanning
 * (spec 9.4, policy 6.3). Checksum validation happens locally before the
 * organization-scoped inventory lookup, preserving the distinction between
 * a transcription error and an unknown but well-formed code.
 */
export function AssetCodeCheckPage() {
  const [rawInput, setRawInput] = useState("");
  const [result, setResult] = useState<AssetCodeValidation | undefined>(undefined);
  const [asset, setAsset] = useState<AssetRecord>();
  const [lookupError, setLookupError] = useState<string>();
  const [loading, setLoading] = useState(false);
  const errorRef = useRef<HTMLDivElement>(null);
  const lookupToken = useRef(0);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const token = ++lookupToken.current;
    const validation = validateAssetCode(rawInput);
    setResult(validation);
    setAsset(undefined);
    setLookupError(undefined);
    if (!validation.valid) {
      // Move focus to the error so screen-reader and keyboard users land on
      // the correction they need (policy 6.3: dialogs/validation manage focus).
      queueMicrotask(() => errorRef.current?.focus());
      return;
    }
    setLoading(true);
    const lookup = await findAssetByCode(validation.normalized);
    if (token !== lookupToken.current) return;
    setLoading(false);
    if (lookup.kind === "error") {
      setLookupError(
        lookup.error.status === 404
          ? "That code is valid, but it is not assigned to an asset in this organization."
          : errorMessage(lookup.error),
      );
      queueMicrotask(() => errorRef.current?.focus());
      return;
    }
    setAsset(lookup.data);
  }

  return (
    <>
      <PageHeading
        title="Find an asset"
        description="Scan or type the six-character code printed on an asset label."
      />
      <Stack component="form" spacing={2} onSubmit={handleSubmit} noValidate sx={{ maxWidth: 360 }}>
        <TextField
          id="asset-code-input"
          label="Public asset code"
          placeholder="7K3MXY"
          value={rawInput}
          onChange={(event) => {
            lookupToken.current += 1;
            setLoading(false);
            setRawInput(event.target.value);
            setResult(undefined);
            setAsset(undefined);
            setLookupError(undefined);
          }}
          slotProps={{ htmlInput: { autoComplete: "off", "aria-describedby": "asset-code-hint" } }}
          autoFocus
        />
        <Typography id="asset-code-hint" variant="body2" color="text.secondary">
          Letters and digits only; spaces and hyphens are ignored.
        </Typography>
        <Button
          type="submit"
          variant="contained"
          disabled={loading}
          sx={{ alignSelf: "flex-start" }}
        >
          {loading ? "Looking up…" : "Find asset"}
        </Button>

        {result && !result.valid ? (
          <Alert severity="error" role="alert" tabIndex={-1} ref={errorRef}>
            {REASON_MESSAGES[result.reason]}
          </Alert>
        ) : null}
        {lookupError ? (
          <Alert severity="error" role="alert" tabIndex={-1} ref={errorRef}>
            {lookupError}
          </Alert>
        ) : null}
        {asset ? (
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="h3">{asset.displayName}</Typography>
            <Typography color="text.secondary">{asset.assetModelName}</Typography>
            <Typography sx={{ fontFamily: "monospace", fontWeight: 800, letterSpacing: 2, my: 1 }}>
              {asset.publicCode}
            </Typography>
            {asset.lifecycleState === "LOST" ? (
              <Alert severity="error" sx={{ mb: 2 }}>
                This asset is marked lost.
              </Alert>
            ) : null}
            <Button component={RouterLink} to={`/inventory/assets/${asset.id}`} variant="contained">
              Open asset
            </Button>
          </Paper>
        ) : null}
      </Stack>
    </>
  );
}
