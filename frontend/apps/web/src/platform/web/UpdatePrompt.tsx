import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Stack from "@mui/material/Stack";
import { useRegisterSW } from "virtual:pwa-register/react";

/**
 * Service-worker update banner. `vite-plugin-pwa`'s `autoUpdate` mode
 * installs new precached assets in the background; this surfaces the
 * "ready to reload" moment so a mid-audit user is never silently swapped to
 * new code, and offers an explicit reload action.
 */
export function UpdatePrompt() {
  const { needRefresh, updateServiceWorker } = useRegisterSW();
  const [needsRefresh] = needRefresh;

  if (!needsRefresh) {
    return null;
  }

  return (
    <Alert
      severity="info"
      role="status"
      action={
        <Button color="inherit" size="small" onClick={() => void updateServiceWorker(true)}>
          Reload
        </Button>
      }
      sx={{ borderRadius: 0 }}
    >
      <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
        A new version of BigContainers is available.
      </Stack>
    </Alert>
  );
}
