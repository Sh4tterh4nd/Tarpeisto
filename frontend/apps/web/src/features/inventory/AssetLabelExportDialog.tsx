import { useMemo, useState } from "react";
import DownloadIcon from "@mui/icons-material/Download";
import PrintOutlinedIcon from "@mui/icons-material/PrintOutlined";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import {
  createAssetLabelCalibration,
  createAssetLabelPdf,
  createPtouchCsv,
  downloadAssetLabelFile,
  type AssetLabelCalibrationInput,
  type AssetLabelPdfInput,
} from "./assetLabelApi";
import { errorMessage } from "./inventoryApi";

type LabelFormat = AssetLabelPdfInput["format"];

interface LabelFormatDefinition {
  label: string;
  labelWidthMm: number;
  labelHeightMm: number;
  columns: number;
  rows: number;
  defaultMarginLeftMm: number;
  defaultMarginTopMm: number;
}

const FORMAT_DEFINITIONS: Record<LabelFormat, LabelFormatDefinition> = {
  A4_70X36_24: {
    label: "70 × 36 mm · 24-up A4",
    labelWidthMm: 70,
    labelHeightMm: 36,
    columns: 3,
    rows: 8,
    defaultMarginLeftMm: 0,
    defaultMarginTopMm: 4.5,
  },
  A4_97X42_3_12: {
    label: "97 × 42.3 mm · 12-up A4",
    labelWidthMm: 97,
    labelHeightMm: 42.3,
    columns: 2,
    rows: 6,
    defaultMarginLeftMm: 8,
    defaultMarginTopMm: 21.6,
  },
};

interface CalibrationValues {
  marginLeftMm: string;
  marginTopMm: string;
  horizontalPitchMm: string;
  verticalPitchMm: string;
}

function calibrationDefaults(format: LabelFormat): CalibrationValues {
  const definition = FORMAT_DEFINITIONS[format];
  return {
    marginLeftMm: String(definition.defaultMarginLeftMm),
    marginTopMm: String(definition.defaultMarginTopMm),
    horizontalPitchMm: String(definition.labelWidthMm),
    verticalPitchMm: String(definition.labelHeightMm),
  };
}

function toFiniteNumber(value: string): number | undefined {
  if (!value.trim()) return undefined;
  const number = Number(value);
  return Number.isFinite(number) ? number : undefined;
}

function gutterLabel(value: number): string {
  return `${value.toFixed(2).replace(/\.00$/, "")} mm`;
}

interface ValidationResult {
  calibration?: NonNullable<AssetLabelPdfInput["calibration"]>;
  error?: string;
}

function validateCalibration(format: LabelFormat, values: CalibrationValues): ValidationResult {
  const definition = FORMAT_DEFINITIONS[format];
  const marginLeftMm = toFiniteNumber(values.marginLeftMm);
  const marginTopMm = toFiniteNumber(values.marginTopMm);
  const horizontalPitchMm = toFiniteNumber(values.horizontalPitchMm);
  const verticalPitchMm = toFiniteNumber(values.verticalPitchMm);
  if (
    marginLeftMm === undefined ||
    marginTopMm === undefined ||
    horizontalPitchMm === undefined ||
    verticalPitchMm === undefined
  ) {
    return { error: "Enter a number for every calibration value." };
  }
  if (marginLeftMm < 0 || marginTopMm < 0) {
    return { error: "Margins cannot be negative." };
  }
  if (horizontalPitchMm < definition.labelWidthMm || verticalPitchMm < definition.labelHeightMm) {
    return { error: "Pitch must be at least the label size so labels do not overlap." };
  }
  if (
    marginLeftMm + (definition.columns - 1) * horizontalPitchMm + definition.labelWidthMm > 210 ||
    marginTopMm + (definition.rows - 1) * verticalPitchMm + definition.labelHeightMm > 297
  ) {
    return { error: "These margins and pitches place labels outside the A4 page." };
  }
  return {
    calibration: { marginLeftMm, marginTopMm, horizontalPitchMm, verticalPitchMm },
  };
}

function filenameSuffix(format: LabelFormat): string {
  return format === "A4_70X36_24" ? "70x36" : "97x42-3";
}

export function AssetLabelExportDialog({
  assetIds,
  onClose,
}: {
  assetIds: string[];
  onClose: () => void;
}) {
  const [format, setFormat] = useState<LabelFormat>("A4_70X36_24");
  const [skipFirstPositions, setSkipFirstPositions] = useState("0");
  const [calibration, setCalibration] = useState(() => calibrationDefaults("A4_70X36_24"));
  const [error, setError] = useState<string>();
  const [busyAction, setBusyAction] = useState<"labels" | "calibration" | "csv">();
  const definition = FORMAT_DEFINITIONS[format];
  const parsedSkip = Number(skipFirstPositions);
  const validSkip =
    Number.isInteger(parsedSkip) &&
    parsedSkip >= 0 &&
    parsedSkip < definition.columns * definition.rows;
  const calibrationValidation = useMemo(
    () => validateCalibration(format, calibration),
    [calibration, format],
  );
  const horizontalGutter =
    toFiniteNumber(calibration.horizontalPitchMm) === undefined
      ? undefined
      : toFiniteNumber(calibration.horizontalPitchMm)! - definition.labelWidthMm;
  const verticalGutter =
    toFiniteNumber(calibration.verticalPitchMm) === undefined
      ? undefined
      : toFiniteNumber(calibration.verticalPitchMm)! - definition.labelHeightMm;

  function setCalibrationValue(key: keyof CalibrationValues, value: string) {
    setCalibration((current) => ({ ...current, [key]: value }));
    setError(undefined);
  }

  function changeFormat(nextFormat: LabelFormat) {
    setFormat(nextFormat);
    setCalibration(calibrationDefaults(nextFormat));
    setSkipFirstPositions("0");
    setError(undefined);
  }

  async function createCalibrationPdf() {
    if (!calibrationValidation.calibration || busyAction) {
      setError(calibrationValidation.error);
      return;
    }
    setBusyAction("calibration");
    setError(undefined);
    const input: AssetLabelCalibrationInput = {
      format,
      calibration: calibrationValidation.calibration,
    };
    const result = await createAssetLabelCalibration(input);
    setBusyAction(undefined);
    if (result.kind === "error") {
      setError(errorMessage(result.error));
      return;
    }
    downloadAssetLabelFile(result.data, `asset-label-calibration-${filenameSuffix(format)}.pdf`);
  }

  async function createLabelsPdf() {
    if (busyAction) return;
    if (!validSkip) {
      setError(
        `Skip must be a whole number from 0 to ${definition.columns * definition.rows - 1}.`,
      );
      return;
    }
    if (!calibrationValidation.calibration) {
      setError(calibrationValidation.error);
      return;
    }
    setBusyAction("labels");
    setError(undefined);
    const result = await createAssetLabelPdf({
      assetIds,
      format,
      skipFirstPositions: parsedSkip,
      calibration: calibrationValidation.calibration,
    });
    setBusyAction(undefined);
    if (result.kind === "error") {
      setError(errorMessage(result.error));
      return;
    }
    downloadAssetLabelFile(result.data, `asset-labels-${filenameSuffix(format)}.pdf`);
  }

  async function createCsv() {
    if (busyAction) return;
    setBusyAction("csv");
    setError(undefined);
    const result = await createPtouchCsv({ assetIds });
    setBusyAction(undefined);
    if (result.kind === "error") {
      setError(errorMessage(result.error));
      return;
    }
    downloadAssetLabelFile(result.data, "asset-labels-ptouch.csv");
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Export asset labels</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {error ? <Alert severity="error">{error}</Alert> : null}
          <Alert severity="info">
            {assetIds.length} {assetIds.length === 1 ? "unit is" : "units are"} selected. Start with
            a calibration page whenever you change label stock or printer settings.
          </Alert>
          <TextField
            select
            label="A4 label stock"
            value={format}
            onChange={(event) => changeFormat(event.target.value as LabelFormat)}
          >
            {Object.entries(FORMAT_DEFINITIONS).map(([value, item]) => (
              <MenuItem key={value} value={value}>
                {item.label}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label="Skip first positions"
            type="number"
            value={skipFirstPositions}
            onChange={(event) => {
              setSkipFirstPositions(event.target.value);
              setError(undefined);
            }}
            error={!validSkip}
            helperText={`Use 0–${definition.columns * definition.rows - 1} for a partially used sheet.`}
            slotProps={{
              htmlInput: { min: 0, max: definition.columns * definition.rows - 1, step: 1 },
            }}
          />
          <Box sx={{ borderTop: 1, borderColor: "divider", pt: 2 }}>
            <Typography variant="subtitle2" sx={{ mb: 0.5 }}>
              Sheet calibration (mm)
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
              Pitches position each label’s top-left corner. Gutters are calculated from those
              values.
            </Typography>
            <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5}>
              <TextField
                label="Left margin"
                type="number"
                value={calibration.marginLeftMm}
                onChange={(event) => setCalibrationValue("marginLeftMm", event.target.value)}
                slotProps={{ htmlInput: { min: 0, step: 0.1 } }}
              />
              <TextField
                label="Top margin"
                type="number"
                value={calibration.marginTopMm}
                onChange={(event) => setCalibrationValue("marginTopMm", event.target.value)}
                slotProps={{ htmlInput: { min: 0, step: 0.1 } }}
              />
            </Stack>
            <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5} sx={{ mt: 1.5 }}>
              <TextField
                label="Horizontal pitch"
                type="number"
                value={calibration.horizontalPitchMm}
                onChange={(event) => setCalibrationValue("horizontalPitchMm", event.target.value)}
                slotProps={{ htmlInput: { min: definition.labelWidthMm, step: 0.1 } }}
              />
              <TextField
                label="Vertical pitch"
                type="number"
                value={calibration.verticalPitchMm}
                onChange={(event) => setCalibrationValue("verticalPitchMm", event.target.value)}
                slotProps={{ htmlInput: { min: definition.labelHeightMm, step: 0.1 } }}
              />
            </Stack>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5 }}>
              Derived gutters: horizontal{" "}
              {horizontalGutter === undefined ? "—" : gutterLabel(horizontalGutter)}; vertical{" "}
              {verticalGutter === undefined ? "—" : gutterLabel(verticalGutter)}.
            </Typography>
            {calibrationValidation.error ? (
              <Typography color="error" variant="body2" sx={{ mt: 0.5 }}>
                {calibrationValidation.error}
              </Typography>
            ) : null}
          </Box>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ alignItems: "center", flexWrap: "wrap", gap: 0.5, px: 3, pb: 2 }}>
        <Button onClick={onClose}>Close</Button>
        <Box sx={{ flex: 1 }} />
        <Button
          startIcon={<DownloadIcon />}
          onClick={() => void createCsv()}
          disabled={Boolean(busyAction)}
        >
          {busyAction === "csv" ? "Preparing CSV…" : "P-touch CSV"}
        </Button>
        <Button
          startIcon={<PrintOutlinedIcon />}
          onClick={() => void createCalibrationPdf()}
          disabled={Boolean(busyAction) || !calibrationValidation.calibration}
        >
          {busyAction === "calibration" ? "Preparing…" : "Calibration PDF"}
        </Button>
        <Button
          variant="contained"
          startIcon={<PrintOutlinedIcon />}
          onClick={() => void createLabelsPdf()}
          disabled={Boolean(busyAction) || !validSkip || !calibrationValidation.calibration}
        >
          {busyAction === "labels" ? "Preparing…" : "Labels PDF"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
