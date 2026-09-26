import { useRef, useState, type FormEvent } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { PageHeading } from "@bigcontainers/shared-ui";
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
 * (spec 9.4, policy 6.3). Phase 0 only wires up client-side normalization
 * and checksum validation; looking a valid code up against inventory is a
 * later phase.
 */
export function AssetCodeCheckPage() {
  const [rawInput, setRawInput] = useState("");
  const [result, setResult] = useState<AssetCodeValidation | undefined>(undefined);
  const errorRef = useRef<HTMLDivElement>(null);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const validation = validateAssetCode(rawInput);
    setResult(validation);
    if (!validation.valid) {
      // Move focus to the error so screen-reader and keyboard users land on
      // the correction they need (policy 6.3: dialogs/validation manage focus).
      queueMicrotask(() => errorRef.current?.focus());
    }
  }

  return (
    <>
      <PageHeading
        title="Check an asset code"
        description="Enter a six-character public asset code to validate it before looking it up."
      />
      <Stack component="form" spacing={2} onSubmit={handleSubmit} noValidate sx={{ maxWidth: 360 }}>
        <TextField
          id="asset-code-input"
          label="Public asset code"
          placeholder="7K3MXY"
          value={rawInput}
          onChange={(event) => setRawInput(event.target.value)}
          slotProps={{ htmlInput: { autoComplete: "off", "aria-describedby": "asset-code-hint" } }}
          autoFocus
        />
        <Typography id="asset-code-hint" variant="body2" color="text.secondary">
          Letters and digits only; spaces and hyphens are ignored.
        </Typography>
        <Button type="submit" variant="contained" sx={{ alignSelf: "flex-start" }}>
          Validate code
        </Button>

        {result?.valid === true ? (
          <Alert severity="success" role="status">
            {result.normalized} checks out.
          </Alert>
        ) : null}
        {result && !result.valid ? (
          <Alert severity="error" role="alert" tabIndex={-1} ref={errorRef}>
            {REASON_MESSAGES[result.reason]}
          </Alert>
        ) : null}
      </Stack>
    </>
  );
}
