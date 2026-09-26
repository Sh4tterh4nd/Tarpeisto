import { Route, Routes } from "react-router-dom";
import { ApplicationInfoPage } from "../features/application-info/ApplicationInfoPage";
import { AssetCodeCheckPage } from "../features/asset-code/AssetCodeCheckPage";
import { RequireRole } from "../features/identity/RequireRole";
import { SignInPage } from "../features/identity/SignInPage";
import { UsersPage } from "../features/identity/UsersPage";
import { NotFoundPage } from "./NotFoundPage";

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<ApplicationInfoPage />} />
      <Route path="/asset-code" element={<AssetCodeCheckPage />} />
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
