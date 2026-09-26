import { lazy, Suspense } from "react";
import CircularProgress from "@mui/material/CircularProgress";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { Route, Routes } from "react-router-dom";
import { ApplicationInfoPage } from "../features/application-info/ApplicationInfoPage";
import { AssetCodeCheckPage } from "../features/asset-code/AssetCodeCheckPage";
import { RequireRole } from "../features/identity/RequireRole";
import { SignInPage } from "../features/identity/SignInPage";
import { UsersPage } from "../features/identity/UsersPage";
import { NotFoundPage } from "./NotFoundPage";

const CatalogPage = lazy(() =>
  import("../features/inventory/CatalogPage").then((module) => ({ default: module.CatalogPage })),
);
const AssetModelPage = lazy(() =>
  import("../features/inventory/AssetModelPage").then((module) => ({
    default: module.AssetModelPage,
  })),
);
const AssetPage = lazy(() =>
  import("../features/inventory/AssetPage").then((module) => ({ default: module.AssetPage })),
);

const INVENTORY_ROLES = ["OWNER", "DEPUTY", "OPERATOR_AUDITOR", "VIEWER"] as const;

function LoadingPage() {
  return (
    <Stack direction="row" spacing={2} sx={{ alignItems: "center", py: 5 }}>
      <CircularProgress size={24} />
      <Typography role="status">Loading inventory.</Typography>
    </Stack>
  );
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<ApplicationInfoPage />} />
      <Route
        path="/asset-code"
        element={
          <RequireRole allow={[...INVENTORY_ROLES]}>
            <AssetCodeCheckPage />
          </RequireRole>
        }
      />
      <Route
        path="/inventory"
        element={
          <RequireRole allow={[...INVENTORY_ROLES]}>
            <Suspense fallback={<LoadingPage />}>
              <CatalogPage />
            </Suspense>
          </RequireRole>
        }
      />
      <Route
        path="/inventory/models/:assetModelId"
        element={
          <RequireRole allow={[...INVENTORY_ROLES]}>
            <Suspense fallback={<LoadingPage />}>
              <AssetModelPage />
            </Suspense>
          </RequireRole>
        }
      />
      <Route
        path="/inventory/assets/:assetId"
        element={
          <RequireRole allow={[...INVENTORY_ROLES]}>
            <Suspense fallback={<LoadingPage />}>
              <AssetPage />
            </Suspense>
          </RequireRole>
        }
      />
      <Route path="/sign-in" element={<SignInPage />} />
      <Route
        path="/admin/users"
        element={
          <RequireRole allow={["OWNER"]}>
            <UsersPage />
          </RequireRole>
        }
      />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
