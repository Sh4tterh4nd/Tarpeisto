import { useEffect, useEffectEvent, useRef, useState } from "react";
import PauseIcon from "@mui/icons-material/Pause";
import PlayArrowIcon from "@mui/icons-material/PlayArrow";
import RefreshIcon from "@mui/icons-material/Refresh";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import type {
  QrCameraDevice,
  QrScanSession,
  QrScannerCapability,
  QrScannerEngine,
} from "../../platform/capabilities/QrScannerCapability";

type ViewportStatus =
  | "ready"
  | "starting"
  | "running"
  | "paused"
  | "permission-denied"
  | "no-camera"
  | "unsupported"
  | "insecure"
  | "decoder-error"
  | "reconnecting";

function problemStatus(
  error: unknown,
): Exclude<ViewportStatus, "ready" | "starting" | "running" | "paused"> {
  if (error instanceof DOMException) {
    if (error.name === "NotAllowedError" || error.name === "PermissionDeniedError") {
      return "permission-denied";
    }
    if (error.name === "NotFoundError" || error.name === "OverconstrainedError") return "no-camera";
    if (error.name === "SecurityError") return "insecure";
  }
  return "decoder-error";
}

const STATUS_COPY: Record<ViewportStatus, string> = {
  ready: "Camera is ready when you are.",
  starting: "Requesting camera access.",
  running: "Looking continuously for a Tarpeisto QR code.",
  paused: "Camera paused. Resume when the label is in view.",
  "permission-denied":
    "Camera permission was denied. Allow camera access in your browser, or use manual entry.",
  "no-camera": "No usable camera was found. Connect or select a camera, or use manual entry.",
  unsupported: "This browser does not expose camera scanning. Manual entry remains available.",
  insecure:
    "Camera scanning needs a secure HTTPS connection (or localhost). Manual entry remains available.",
  "decoder-error":
    "The camera started but its QR decoder could not run. Try another camera or use manual entry.",
  reconnecting: "The camera connection ended. Reconnect to continue scanning.",
};

export interface ScannerViewportProps {
  readonly capability: QrScannerCapability;
  readonly onCode: (rawCode: string) => void;
}

/**
 * The camera deck is intentionally self-contained: every session stops its
 * tracks when this component pauses, switches devices, or unmounts.
 */
export function ScannerViewport({ capability, onCode }: ScannerViewportProps) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [status, setStatus] = useState<ViewportStatus>(() => {
    const availability = capability.availability();
    return availability === "ready" ? "ready" : availability;
  });
  // Request camera access once on entry. A denial or failure turns this off, so
  // the user retains an explicit retry without a permission-request loop.
  const [shouldRun, setShouldRun] = useState(() => capability.availability() === "ready");
  const [cameraId, setCameraId] = useState("");
  const [cameras, setCameras] = useState<QrCameraDevice[]>([]);
  const [engine, setEngine] = useState<QrScannerEngine>();
  const deliverCode = useEffectEvent((code: string) => onCode(code));

  useEffect(() => {
    if (!shouldRun || !videoRef.current) return;
    let disposed = false;
    let session: QrScanSession | undefined;

    const start = async () => {
      const availability = capability.availability();
      if (availability !== "ready") {
        setStatus(availability);
        setShouldRun(false);
        return;
      }
      setStatus("starting");
      setEngine(undefined);
      try {
        session = await capability.start(
          videoRef.current as HTMLVideoElement,
          cameraId || undefined,
          (code) => deliverCode(code),
          () => {
            if (!disposed) {
              setStatus("reconnecting");
              setShouldRun(false);
            }
          },
        );
        if (disposed) {
          session.stop();
          return;
        }
        const foundCameras = await capability.listCameras();
        if (disposed) return;
        setCameras(foundCameras);
        setEngine(session.engine);
        setStatus("running");
      } catch (error) {
        if (disposed) return;
        setStatus(problemStatus(error));
        setShouldRun(false);
      }
    };
    void start();

    return () => {
      disposed = true;
      session?.stop();
    };
  }, [cameraId, capability, shouldRun]);

  const canStart = status !== "unsupported" && status !== "insecure";
  const isRunning = status === "running" || status === "starting";

  return (
    <Paper
      component="section"
      aria-labelledby="camera-deck-heading"
      variant="outlined"
      sx={{
        overflow: "hidden",
        borderColor: "primary.main",
        boxShadow: "0 12px 26px rgb(14 69 54 / 16%)",
      }}
    >
      <Box sx={{ bgcolor: "primary.main", color: "primary.contrastText", px: 2.5, py: 1.75 }}>
        <Typography id="camera-deck-heading" variant="h3">
          Live equipment check-in
        </Typography>
        <Typography variant="body2" sx={{ opacity: 0.88 }}>
          Hold the printed code inside the frame. Scans continue until you pause the camera.
        </Typography>
      </Box>
      <Stack direction={{ xs: "column", md: "row" }} sx={{ minHeight: { md: 360 } }}>
        <Box
          sx={{
            position: "relative",
            flex: 1,
            minHeight: 300,
            bgcolor: "grey.900",
            display: "grid",
            placeItems: "center",
          }}
        >
          <video
            ref={videoRef}
            muted
            playsInline
            aria-label="Live camera preview for QR scanning"
            style={{ width: "100%", height: "100%", minHeight: 300, objectFit: "cover" }}
          />
          <Box
            aria-hidden="true"
            sx={{
              position: "absolute",
              width: "min(62vw, 250px)",
              aspectRatio: "1",
              border: "3px solid",
              borderColor: "warning.main",
              borderRadius: 2,
              boxShadow: "0 0 0 200vmax rgb(0 0 0 / 28%)",
              pointerEvents: "none",
            }}
          />
        </Box>
        <Stack
          spacing={2}
          sx={{ width: { xs: "auto", md: 300 }, p: 2.5, bgcolor: "background.paper" }}
        >
          <Box>
            <Typography variant="overline" color="text.secondary">
              Scanner status
            </Typography>
            <Typography role="status" sx={{ fontWeight: 700 }}>
              {STATUS_COPY[status]}
            </Typography>
            {engine ? (
              <Typography variant="caption" color="text.secondary">
                {engine === "native"
                  ? "Fast browser decoder active."
                  : "Portable QR decoder active."}
              </Typography>
            ) : null}
          </Box>

          {status !== "ready" &&
          status !== "running" &&
          status !== "starting" &&
          status !== "paused" ? (
            <Alert severity={status === "reconnecting" ? "warning" : "info"}>
              {STATUS_COPY[status]}
            </Alert>
          ) : null}

          {cameras.length > 1 ? (
            <TextField
              select
              label="Camera"
              value={cameraId}
              onChange={(event) => setCameraId(event.target.value)}
              disabled={isRunning && status === "starting"}
              helperText="Switching cameras briefly pauses and restarts the scanner."
            >
              <MenuItem value="">Rear camera (automatic)</MenuItem>
              {cameras.map((camera) => (
                <MenuItem key={camera.deviceId} value={camera.deviceId}>
                  {camera.label}
                </MenuItem>
              ))}
            </TextField>
          ) : null}

          {isRunning ? (
            <Button
              variant="outlined"
              color="inherit"
              startIcon={status === "starting" ? <CircularProgress size={18} /> : <PauseIcon />}
              onClick={() => {
                setStatus("paused");
                setShouldRun(false);
              }}
              disabled={status === "starting"}
              sx={{ minHeight: 44 }}
            >
              {status === "starting" ? "Start camera" : "Pause camera"}
            </Button>
          ) : (
            <Button
              variant="contained"
              startIcon={status === "reconnecting" ? <RefreshIcon /> : <PlayArrowIcon />}
              onClick={() => setShouldRun(true)}
              disabled={!canStart}
              sx={{ minHeight: 44 }}
            >
              {status === "reconnecting" ? "Reconnect camera" : "Start camera"}
            </Button>
          )}
        </Stack>
      </Stack>
    </Paper>
  );
}
