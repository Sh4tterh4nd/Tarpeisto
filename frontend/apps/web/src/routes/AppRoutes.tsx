import { lazy, Suspense } from "react";
import CircularProgress from "@mui/material/CircularProgress";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { Navigate, Route, Routes } from "react-router-dom";
import { ApplicationInfoPage } from "../features/application-info/ApplicationInfoPage";
import { RequireRole } from "../features/identity/RequireRole";
import { SignInPage } from "../features/identity/SignInPage";
import { UsersPage } from "../features/identity/UsersPage";
import { NotFoundPage } from "./NotFoundPage";

const CatalogPage = lazy(() =>
  import("../features/inventory/CatalogPage").then((module) => ({ default: module.CatalogPage })),
);
const AssetsPage = lazy(() =>
  import("../features/inventory/AssetsPage").then((module) => ({ default: module.AssetsPage })),
);
const AssetModelPage = lazy(() =>
  import("../features/inventory/AssetModelPage").then((module) => ({
    default: module.AssetModelPage,
  })),
);
const AssetPage = lazy(() =>
  import("../features/inventory/AssetPage").then((module) => ({ default: module.AssetPage })),
);
const LocationsPage = lazy(() =>
  import("../features/inventory/LocationsPage").then((module) => ({
    default: module.LocationsPage,
  })),
);
const ScannerPage = lazy(() =>
  import("../features/scanner/ScannerPage").then((module) => ({ default: module.ScannerPage })),
);
const ScannerResultPage = lazy(() =>
  import("../features/scanner/ScannerResultPage").then((module) => ({
    default: module.ScannerResultPage,
  })),
);
const EventsPage = lazy(() =>
  import("../features/events/EventsPage").then((module) => ({ default: module.EventsPage })),
);
const EventDetailPage = lazy(() =>
  import("../features/events/EventDetailPage").then((module) => ({
    default: module.EventDetailPage,
  })),
);
const AuditTaskPage = lazy(() =>
  import("../features/audits/AuditTaskPage").then((module) => ({ default: module.AuditTaskPage })),
);
const ReviewPage = lazy(() =>
  import("../features/review/ReviewPage").then((module) => ({ default: module.ReviewPage })),
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
        path="/scan"
        element={
          <RequireRole allow={[...INVENTORY_ROLES]}>
            <Suspense fallback={<LoadingPage />}>
              <ScannerPage />
            </Suspense>
          </RequireRole>
        }
      />
      <Route
        path="/scan/assets/:assetId"
        element={
          <RequireRole allow={[...INVENTORY_ROLES]}>
            <Suspense fallback={<LoadingPage />}>
              <ScannerResultPage />
            </Suspense>
          </RequireRole>
        }
      />
      <Route path="/asset-code" element={<Navigate to="/scan" replace />} />
      <Route
        path="/events"
        element={
          <RequireRole allow={[...INVENTORY_ROLES]}>
            <Suspense fallback={<LoadingPage />}>
              <EventsPage />
            </Suspense>
          </RequireRole>
        }
      />
      <Route
        path="/events/:bookingId"
        element={
          <RequireRole allow={[...INVENTORY_ROLES]}>
            <Suspense fallback={<LoadingPage />}>
              <EventDetailPage />
            </Suspense>
          </RequireRole>
        }
      />
      <Route
        path="/review"
        element={
          <RequireRole allow={["OWNER", "DEPUTY"]}>
            <Suspense fallback={<LoadingPage />}>
              <ReviewPage />
            </Suspense>
          </RequireRole>
        }
      />
      <Route
        path="/audits/tasks/:taskId"
        element={
          <RequireRole allow={["OWNER", "DEPUTY", "OPERATOR_AUDITOR"]}>
            <Suspense fallback={<LoadingPage />}>
              <AuditTaskPage />
            </Suspense>
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
        path="/inventory/assets"
        element={
          <RequireRole allow={[...INVENTORY_ROLES]}>
            <Suspense fallback={<LoadingPage />}>
              <AssetsPage />
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
      <Route
        path="/inventory/locations"
        element={
          <RequireRole allow={[...INVENTORY_ROLES]}>
            <Suspense fallback={<LoadingPage />}>
              <LocationsPage />
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
