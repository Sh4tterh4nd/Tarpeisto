import Chip from "@mui/material/Chip";
import CloudDoneIcon from "@mui/icons-material/CloudDone";
import CloudOffIcon from "@mui/icons-material/CloudOff";
import SyncIcon from "@mui/icons-material/Sync";
import SyncProblemIcon from "@mui/icons-material/SyncProblem";
import { useConnectivity } from "../capabilities/useConnectivity";
import type { ConnectivityStatus } from "../capabilities/ConnectivityCapability";
import { webConnectivityCapability } from "./webConnectivityCapability";

const STATUS_PRESENTATION: Record<
  ConnectivityStatus,
  { label: string; color: "success" | "default" | "warning" | "error"; icon: React.ReactElement }
> = {
  online: { label: "Online", color: "success", icon: <CloudDoneIcon /> },
  offline: { label: "Offline", color: "warning", icon: <CloudOffIcon /> },
  synchronizing: { label: "Synchronizing", color: "default", icon: <SyncIcon /> },
  failed: { label: "Sync failed", color: "error", icon: <SyncProblemIcon /> },
};

/**
 * Visible online/offline/synchronizing/failed indicator (spec 24.2). Status
 * is never conveyed by color alone: each state also has a distinct label
 * and icon. Backed by {@link useConnectivity} and the web capability
 * implementation, never by reading `navigator.onLine` here directly.
 */
export function ConnectivityChip() {
  const status = useConnectivity(webConnectivityCapability);
  const presentation = STATUS_PRESENTATION[status];

  return (
    <Chip
      icon={presentation.icon}
      label={presentation.label}
      color={presentation.color}
      size="small"
      variant="filled"
      sx={{ color: "inherit", "& .MuiChip-icon": { color: "inherit" } }}
      role="status"
    />
  );
}
