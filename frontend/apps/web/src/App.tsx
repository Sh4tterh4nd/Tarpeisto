import CssBaseline from "@mui/material/CssBaseline";
import { ThemeProvider } from "@mui/material/styles";
import useMediaQuery from "@mui/material/useMediaQuery";
import { useMemo } from "react";
import List from "@mui/material/List";
import ListItemButton from "@mui/material/ListItemButton";
import ListItemText from "@mui/material/ListItemText";
import { Link as RouterLink, useLocation } from "react-router-dom";
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
  const location = useLocation();
  const entries = [
    ["Status", "/"],
    ["Inventory models", "/inventory"],
    ["Assets", "/inventory/assets"],
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
          accountSlot={<AccountNav />}
        >
          <AppRoutes />
        </AppShellLayout>
      </SessionProvider>
    </ThemeProvider>
  );
}
