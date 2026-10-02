import type { ReactNode } from "react";
import AppBar from "@mui/material/AppBar";
import Box from "@mui/material/Box";
import Container from "@mui/material/Container";
import Drawer from "@mui/material/Drawer";
import IconButton from "@mui/material/IconButton";
import Toolbar from "@mui/material/Toolbar";
import Typography from "@mui/material/Typography";
import { useState } from "react";

export interface AppShellLayoutProps {
  /** Product/application title shown in the top bar. */
  title: string;
  /** Rendered at the end of the top bar, typically a connectivity indicator. */
  statusSlot?: ReactNode;
  /** Rendered directly beneath the top bar, e.g. an update-available banner. */
  bannerSlot?: ReactNode;
  /** Primary navigation in the desktop rail and mobile drawer. */
  navSlot?: ReactNode;
  /** Account controls, kept in the quiet top status strip. */
  accountSlot?: ReactNode;
  /** Fit camera workflows below retained navigation and session banners. */
  viewport?: boolean;
  children: ReactNode;
}

/**
 * Tarpeisto-owned page shell with a desktop navigation rail, mobile
 * drawer, status/account strip, and constrained content area.
 */
export function AppShellLayout({
  title,
  statusSlot,
  bannerSlot,
  navSlot,
  accountSlot,
  children,
  viewport = false,
}: AppShellLayoutProps) {
  const [drawerOpen, setDrawerOpen] = useState(false);
  return (
    <Box
      sx={{
        display: "flex",
        minHeight: viewport ? 0 : "100%",
        height: viewport ? "100dvh" : undefined,
        overflow: viewport ? "hidden" : undefined,
      }}
    >
      <Box
        component="aside"
        sx={{
          width: 248,
          flexShrink: 0,
          display: { xs: "none", md: "flex" },
          flexDirection: "column",
          bgcolor: "background.paper",
          borderRight: 1,
          borderColor: "divider",
          position: "sticky",
          top: 0,
          height: viewport ? "100dvh" : "100vh",
        }}
      >
        <Typography variant="h3" component="div" sx={{ px: 3, py: 2.5 }}>
          {title}
        </Typography>
        <Box
          component="nav"
          aria-label="Primary navigation"
          sx={{ px: 1, flexGrow: 1, minHeight: 0, overflowY: viewport ? "auto" : undefined }}
        >
          {navSlot}
        </Box>
      </Box>
      <Drawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        slotProps={{ paper: { sx: { width: 280, pt: 1 } } }}
        ModalProps={{ keepMounted: true }}
      >
        <Typography variant="h3" component="div" sx={{ px: 3, py: 2 }}>
          {title}
        </Typography>
        <Box
          component="nav"
          id="mobile-navigation"
          aria-label="Primary navigation"
          onClick={() => setDrawerOpen(false)}
          sx={{ px: 1 }}
        >
          {navSlot}
        </Box>
      </Drawer>
      <Box
        sx={{ display: "flex", flexDirection: "column", minWidth: 0, flexGrow: 1, minHeight: 0 }}
      >
        <AppBar position="sticky" color="primary" enableColorOnDark elevation={0}>
          <Toolbar
            sx={{
              gap: { xs: 1, sm: 2 },
              flexWrap: viewport ? "nowrap" : { xs: "wrap", sm: "nowrap" },
              py: viewport ? 0 : { xs: 1, sm: 0 },
              minHeight: { xs: 56, sm: 52 },
            }}
          >
            <IconButton
              color="inherit"
              aria-label="Open navigation"
              aria-expanded={drawerOpen}
              aria-controls={drawerOpen ? "mobile-navigation" : undefined}
              onClick={() => setDrawerOpen(true)}
              sx={{ display: { xs: "inline-flex", md: "none" } }}
            >
              <Typography
                component="span"
                aria-hidden="true"
                sx={{ fontSize: "1.4rem", lineHeight: 1 }}
              >
                {"\u2630"}
              </Typography>
            </IconButton>
            <Typography
              variant="h3"
              component="h1"
              sx={{
                flexGrow: 1,
                minWidth: 0,
                overflow: viewport ? "hidden" : undefined,
                textOverflow: "ellipsis",
                whiteSpace: viewport ? "nowrap" : undefined,
                fontSize: { xs: viewport ? "1rem" : "1.2rem", sm: "1.25rem" },
              }}
            >
              {title}
            </Typography>
            {statusSlot ? <Box>{statusSlot}</Box> : null}
            {accountSlot ? (
              <Box
                sx={{
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "flex-end",
                  gap: 1,
                  width: viewport ? "auto" : { xs: "100%", sm: "auto" },
                  "& .MuiButton-root": { minHeight: 44 },
                }}
              >
                {accountSlot}
              </Box>
            ) : null}
          </Toolbar>
        </AppBar>
        {bannerSlot}
        <Container
          component="main"
          maxWidth="xl"
          sx={{
            flexGrow: 1,
            minHeight: 0,
            display: viewport ? "flex" : undefined,
            flexDirection: "column",
            overflow: viewport ? "hidden" : undefined,
            py: viewport ? 1 : { xs: 2, sm: 3 },
          }}
        >
          {children}
        </Container>
      </Box>
    </Box>
  );
}
