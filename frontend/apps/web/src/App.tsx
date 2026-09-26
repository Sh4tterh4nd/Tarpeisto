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
import { useSession } from "./features/identity/useSession";

function PrimaryNav() {
  const { status } = useSession();

  return (
    <Stack direction="row" spacing={0.5} sx={{ alignItems: "center" }}>
      <Button
        component={RouterLink}
        to="/"
        color="inherit"
        sx={{ display: { xs: "none", md: "inline-flex" } }}
      >
        Status
      </Button>
      {status === "authenticated" ? (
        <>
          <Button component={RouterLink} to="/inventory" color="inherit">
            Inventory
          </Button>
          <Button component={RouterLink} to="/asset-code" color="inherit">
            Find asset
          </Button>
        </>
      ) : null}
      <AccountNav />
    </Stack>
  );
}

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
          navSlot={<PrimaryNav />}
        >
          <AppRoutes />
        </AppShellLayout>
      </SessionProvider>
    </ThemeProvider>
  );
}
