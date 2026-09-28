import CssBaseline from "@mui/material/CssBaseline";
import Box from "@mui/material/Box";
import CircularProgress from "@mui/material/CircularProgress";
import { ThemeProvider } from "@mui/material/styles";
import useMediaQuery from "@mui/material/useMediaQuery";
import { useMemo } from "react";
import List from "@mui/material/List";
import ListItemButton from "@mui/material/ListItemButton";
import ListItemText from "@mui/material/ListItemText";
import { Link as RouterLink, Navigate, useLocation } from "react-router-dom";
import { AppShellLayout, createTarpeistoTheme } from "@tarpeisto/shared-ui";
import { AppRoutes } from "./routes/AppRoutes";
import { ConnectivityChip } from "./platform/web/ConnectivityChip";
import { UpdatePrompt } from "./platform/web/UpdatePrompt";
import { AccountNav } from "./features/identity/AccountNav";
import { OidcCallbackAlert } from "./features/identity/OidcCallbackAlert";
import { SessionExpiredBanner } from "./features/identity/SessionExpiredBanner";
import { SessionProvider } from "./features/identity/SessionProvider";
import { useSession } from "./features/identity/useSession";
import { InitialSetupPage } from "./features/setup/InitialSetupPage";

function PrimaryNav() {
  const { status } = useSession();
  const location = useLocation();
  const entries = [
    ["Status", "/"],
    ["Inventory models", "/inventory"],
    ["Assets", "/inventory/assets"],
    ["Containers", "/inventory/containers"],
    ["Locations", "/inventory/locations"],
    ["Events", "/events"],
    ["Scan equipment", "/scan"],
  ] as const;
  const activeDestination = entries
    .filter(
      ([, to]) =>
        location.pathname === to || (to !== "/" && location.pathname.startsWith(`${to}/`)),
    )
    .sort((a, b) => b[1].length - a[1].length)[0]?.[1];

  return (
    <List disablePadding>
      {entries
        .filter(([label]) => label === "Status" || status === "authenticated")
        .map(([label, to]) => (
          <ListItemButton
            key={to}
            component={RouterLink}
            to={to}
            selected={activeDestination === to}
            aria-current={activeDestination === to ? "page" : undefined}
            sx={{ borderRadius: 1, mb: 0.5, minHeight: 44 }}
          >
            <ListItemText primary={label} />
          </ListItemButton>
        ))}
    </List>
  );
}

function AppContent() {
  const { status } = useSession();
  const location = useLocation();

  if (status === "loading") {
    return (
      <Box sx={{ minHeight: "100vh", display: "grid", placeItems: "center" }}>
        <CircularProgress aria-label="Loading application" />
      </Box>
    );
  }

  if (status === "setup-required") {
    return location.pathname === "/setup" ? <InitialSetupPage /> : <Navigate to="/setup" replace />;
  }

  if (location.pathname === "/setup") {
    return <Navigate to={status === "authenticated" ? "/" : "/sign-in"} replace />;
  }

  return (
    <AppShellLayout
      title="Tarpeisto"
      bannerSlot={
        <>
          <OidcCallbackAlert />
          <SessionExpiredBanner />
          <UpdatePrompt />
        </>
      }
      statusSlot={<ConnectivityChip />}
      navSlot={<PrimaryNav />}
      accountSlot={<AccountNav />}
    >
      <AppRoutes />
    </AppShellLayout>
  );
}

export function App() {
  const prefersDark = useMediaQuery("(prefers-color-scheme: dark)");
  const theme = useMemo(() => createTarpeistoTheme(prefersDark ? "dark" : "light"), [prefersDark]);

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <SessionProvider>
        <AppContent />
      </SessionProvider>
    </ThemeProvider>
  );
}
