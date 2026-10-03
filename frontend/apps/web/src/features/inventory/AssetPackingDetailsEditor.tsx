import { AppError } from "@tarpeisto/api-client";
import { useEffect, useRef, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { errorMessage, getAsset, setAssetPackingDetails, type AssetRecord } from "./inventoryApi";
import { normalizedPackingColor, packingHeaderTextColor } from "./packingHeaderColor";

/** A presentation-only section, with its own remote-edit baseline and reload boundary. */
export function AssetPackingDetailsEditor({
  asset,
  onSaved,
  disabled,
  onBusyChange,
}: {
  asset: AssetRecord;
  onSaved: () => Promise<void>;
  disabled?: boolean;
  onBusyChange?: (busy: boolean) => void;
}) {
  const baseline = useRef({
    color: asset.containerColor ?? "#FFFFFF",
    description: asset.unitDescription ?? "",
  });
  const [color, setColor] = useState(asset.containerColor ?? "#FFFFFF"),
    [description, setDescription] = useState(asset.unitDescription ?? "");
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<string>(),
    [saved, setSaved] = useState(false);
  const [conflict, setConflict] = useState(false);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  const validColor = normalizedPackingColor(color);
  async function run(reload: boolean) {
    if (busy || disabled) return;
    setBusy(true);
    onBusyChange?.(true);
    setError(undefined);
    setSaved(false);
    try {
      const fresh = await getAsset(asset.id);
      if (!alive.current) return;
      if (fresh.kind === "error") {
        setError(errorMessage(fresh.error));
        return;
      }
      const current = {
        color: fresh.data.containerColor ?? "#FFFFFF",
        description: fresh.data.unitDescription ?? "",
      };
      if (reload) {
        baseline.current = current;
        setColor(current.color);
        setDescription(current.description);
        setConflict(false);
        return;
      }
      if (
        current.color !== baseline.current.color ||
        current.description !== baseline.current.description
      ) {
        setConflict(true);
        setError("Packing details changed elsewhere. Reload to review them before saving.");
        return;
      }
      if (!validColor || fresh.data.version === undefined) {
        setError("Enter a six-digit header color and reload this asset before saving.");
        return;
      }
      const result = await setAssetPackingDetails(asset.id, {
        containerColor: validColor,
        unitDescription: description,
        expectedVersion: fresh.data.version,
      });
      if (!alive.current) return;
      if (result.kind === "error") {
        setError(errorMessage(result.error));
        if (result.error.status === 409) setConflict(true);
        return;
      }
      baseline.current = {
        color: result.data.containerColor ?? validColor,
        description: result.data.unitDescription ?? "",
      };
      setColor(baseline.current.color);
      setDescription(baseline.current.description);
      setSaved(true);
      await onSaved();
    } catch (failure) {
      if (alive.current)
        setError(
          errorMessage(
            AppError.network(
              failure instanceof Error ? failure : new Error("Packing details could not be saved."),
            ),
          ),
        );
    } finally {
      if (alive.current) {
        setBusy(false);
        onBusyChange?.(false);
      }
    }
  }
  return (
    <Stack spacing={2}>
      <Typography variant="h3">Packing sheet</Typography>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        <Box
          component="input"
          type="color"
          aria-label="Choose packing header color"
          value={validColor ?? "#FFFFFF"}
          onChange={(e) => {
            setColor(e.target.value.toUpperCase());
            setSaved(false);
          }}
          disabled={busy || disabled}
          sx={{ width: 52, height: 44, p: 0, border: 1, borderColor: "divider", flexShrink: 0 }}
        />
        <TextField
          label="Packing header color"
          value={color}
          onChange={(e) => {
            setColor(e.target.value);
            setSaved(false);
          }}
          error={!validColor}
          helperText="Hex color, for example #FFFFFF"
          disabled={busy || disabled}
          fullWidth
        />
      </Stack>
      <TextField
        label="Container description"
        multiline
        minRows={3}
        value={description}
        onChange={(e) => {
          setDescription(e.target.value);
          setSaved(false);
        }}
        helperText={`${description.length}/500 characters`}
        error={description.length > 500}
        disabled={busy || disabled}
      />
      <Box
        aria-label="Packing header preview"
        sx={{
          p: 2,
          border: "1px solid black",
          backgroundColor: validColor ?? "#FFFFFF",
          color: packingHeaderTextColor(validColor ?? "#FFFFFF"),
        }}
      >
        <Typography sx={{ fontWeight: 700, overflowWrap: "anywhere" }}>
          {asset.displayName}
        </Typography>
        <Typography sx={{ overflowWrap: "anywhere" }}>
          Model Type: {asset.assetModelName}
        </Typography>
        <Typography sx={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
          {description}
        </Typography>
      </Box>
      {error ? <Alert severity="error">{error}</Alert> : null}
      {saved ? <Alert severity="success">Packing details saved.</Alert> : null}
      <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap" }}>
        <Button
          variant="outlined"
          disabled={busy || disabled || !validColor || description.length > 500 || conflict}
          onClick={() => void run(false)}
        >
          Save packing details
        </Button>
        <Button disabled={busy || disabled} onClick={() => void run(true)}>
          Reload packing details
        </Button>
      </Stack>
    </Stack>
  );
}
