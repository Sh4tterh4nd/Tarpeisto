import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import CircularProgress from "@mui/material/CircularProgress";
import Chip from "@mui/material/Chip";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { AppError, apiClient, toAppError } from "@bigcontainers/api-client";
import type { components } from "@bigcontainers/api-client";
import { PageHeading } from "@bigcontainers/shared-ui";

type ApplicationInfo = components["schemas"]["ApplicationInfoResponse"];

type LoadState =
  | { status: "loading" }
  | { status: "loaded"; info: ApplicationInfo }
  | { status: "error"; error: AppError };

/**
 * Demonstrates the full Phase 0 plumbing end to end: routing, the shared
 * theme, and a real call through `@bigcontainers/api-client` to
 * `GET /api/v1/application`, including a clear error state.
 */
export function ApplicationInfoPage() {
  const [state, setState] = useState<LoadState>({ status: "loading" });

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const { data, error, response } = await apiClient.GET("/api/v1/application");
        if (cancelled) {
          return;
        }
        // The live backend's OpenAPI document does not declare an error
        // response for this operation (see the api-client package's
        // errors.ts comment), so `data` is not guaranteed non-undefined by
        // the generated types even on success; guard it explicitly.
        //
        // The status is read here, before the guard below narrows anything.
        // Because the document declares no error response, `error` is typed
        // `never`, which collapses the result union inside the guard and
        // would leave `response` unreachable if it were read there.
        const responseStatus = response.status;
        if (error || !data) {
          setState({ status: "error", error: toAppError(error, responseStatus) });
          return;
        }
        setState({ status: "loaded", info: data });
      } catch (cause) {
        if (!cancelled) {
          setState({ status: "error", error: AppError.network(cause) });
        }
      }
    }

    void load();
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <>
      <PageHeading
        title="Application status"
        description="Confirms the browser can reach the BigContainers API."
      />
      <Paper variant="outlined" sx={{ p: 3 }}>
        {state.status === "loading" ? (
          <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
            <CircularProgress size={24} aria-hidden="true" />
            <Typography role="status">Loading application information…</Typography>
          </Stack>
        ) : null}

        {state.status === "loaded" ? (
          <Stack spacing={1.5}>
            <Typography variant="h3" component="p">
              {state.info.applicationName ?? "BigContainers"}
            </Typography>
            <Typography color="text.secondary">
              Version {state.info.version ?? "unknown"}
            </Typography>
            <Chip
              label={
                state.info.oidcConfigured
                  ? `OIDC configured${state.info.oidcProvider?.displayName ? ` (${state.info.oidcProvider.displayName})` : ""}`
                  : "Local login only"
              }
              color={state.info.oidcConfigured ? "success" : "default"}
              sx={{ alignSelf: "flex-start" }}
            />
          </Stack>
        ) : null}

        {state.status === "error" ? (
          <Alert severity="error" role="alert">
            Could not load application information: {state.error.message}
          </Alert>
        ) : null}
      </Paper>
    </>
  );
}
