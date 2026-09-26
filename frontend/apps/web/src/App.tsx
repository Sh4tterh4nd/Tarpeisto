import Stack from "@mui/material/Stack";
import Button from "@mui/material/Button";
import CssBaseline from "@mui/material/CssBaseline";
import { ThemeProvider } from "@mui/material/styles";
import useMediaQuery from "@mui/material/useMediaQuery";
import { useMemo } from "react";
import { Link as RouterLink } from "react-router-dom";
import { AppShellLayout, createBigContainersTheme } from "@bigcontainers/shared-ui";
import { AppRoutes } from "./routes/AppRoutes";
import { ConnectivityChip } from "./platform/web/ConnectivityChip";
import { UpdatePrompt } from "./platform/web/UpdatePrompt";
import { AccountNav } from "./features/identity/AccountNav";
import { OidcCallbackAlert } from "./features/identity/OidcCallbackAlert";
import { SessionExpiredBanner } from "./features/identity/SessionExpiredBanner";
import { SessionProvider } from "./features/identity/SessionProvider";

export function App() {
  const prefersDark = useMediaQuery("(prefers-color-scheme: dark)");
  const theme = useMemo(
    () => createBigContainersTheme(prefersDark ? "dark" : "light"),
    [prefersDark],
  );

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <SessionProvider>
        <AppShellLayout
          title="BigContainers"
          bannerSlot={
            <>
              <OidcCallbackAlert />
              <SessionExpiredBanner />
              <UpdatePrompt />
            </>
          }
          statusSlot={<ConnectivityChip />}
          navSlot={
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <Button component={RouterLink} to="/" color="inherit">
                Status
              </Button>
              <Button component={RouterLink} to="/asset-code" color="inherit">
                Check code
              </Button>
              <AccountNav />
            </Stack>
          }
        >
          <AppRoutes />
        </AppShellLayout>
      </SessionProvider>
    </ThemeProvider>
  );
}
