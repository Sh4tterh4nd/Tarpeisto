import type { ReactNode } from "react";
import AppBar from "@mui/material/AppBar";
import Box from "@mui/material/Box";
import Container from "@mui/material/Container";
import Toolbar from "@mui/material/Toolbar";
import Typography from "@mui/material/Typography";

export interface AppShellLayoutProps {
  /** Product/application title shown in the top bar. */
  title: string;
  /** Rendered at the end of the top bar, typically a connectivity indicator. */
  statusSlot?: ReactNode;
  /** Rendered directly beneath the top bar, e.g. an update-available banner. */
  bannerSlot?: ReactNode;
  /** Primary in-page navigation, rendered as part of the top bar on wide screens. */
  navSlot?: ReactNode;
  children: ReactNode;
}

/**
 * BigContainers-owned page shell: a top app bar with a title, optional
 * navigation and connectivity/status slots, an optional banner row (for
 * example a PWA "update available" prompt), and a constrained content area.
 */
export function AppShellLayout({
  title,
  statusSlot,
  bannerSlot,
  navSlot,
  children,
}: AppShellLayoutProps) {
  return (
    <Box sx={{ display: "flex", flexDirection: "column", minHeight: "100%" }}>
      <AppBar position="sticky" color="primary" enableColorOnDark>
        <Toolbar sx={{ gap: 2 }}>
          <Typography variant="h3" component="h1" sx={{ flexGrow: 1 }}>
            {title}
          </Typography>
          {navSlot}
          {statusSlot}
        </Toolbar>
      </AppBar>
      {bannerSlot}
      <Container component="main" maxWidth="md" sx={{ flexGrow: 1, py: { xs: 2, sm: 3 } }}>
        {children}
      </Container>
    </Box>
  );
}
