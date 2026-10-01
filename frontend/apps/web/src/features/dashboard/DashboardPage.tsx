import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import Divider from "@mui/material/Divider";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { Link as RouterLink } from "react-router-dom";
import { PageHeading } from "@tarpeisto/shared-ui";
import { reconcilePackingUntilComplete, reviewError } from "../review/reviewApi";
import { useSession } from "../identity/useSession";
import {
  getDashboard,
  getDashboardPage,
  type DashboardPage as QueuePage,
  type DashboardQueue,
} from "./dashboardApi";

const labels: Record<DashboardQueue, string> = {
  UPCOMING_EVENTS: "Upcoming events",
  OUTSTANDING_CUSTODY: "Equipment in event custody",
  AUDITS: "Audits to complete",
  REVIEW: "Findings to review",
  REPAIRS: "Open repairs",
  METADATA: "Missing metadata",
  CONTAINERS: "Containers needing attention",
  LOW_STOCK: "Low consumable stock",
};
const sections: { title: string; queues: DashboardQueue[] }[] = [
  { title: "Event handoff", queues: ["UPCOMING_EVENTS", "OUTSTANDING_CUSTODY"] },
  { title: "Audit and review", queues: ["AUDITS", "REVIEW"] },
  { title: "Equipment upkeep", queues: ["CONTAINERS", "REPAIRS", "METADATA", "LOW_STOCK"] },
];
const empty: Record<DashboardQueue, string> = {
  UPCOMING_EVENTS: "No upcoming events need preparation.",
  OUTSTANDING_CUSTODY: "All event equipment has been released from custody.",
  AUDITS: "No audits are waiting.",
  REVIEW: "No findings need review.",
  REPAIRS: "No repairs are open.",
  METADATA: "Active equipment has its required field values.",
  CONTAINERS: "Active containers are complete and available.",
  LOW_STOCK: "Consumables are above their configured thresholds.",
};

export function DashboardPage() {
  const { principal } = useSession();
  const identityKey = JSON.stringify([
    principal?.userId,
    principal?.organizationId,
    principal?.role,
  ]);
  const identity = useRef(identityKey);
  useLayoutEffect(() => {
    identity.current = identityKey;
  }, [identityKey]);
  const canReconcile =
    !principal?.temporaryAccess && (principal?.role === "OWNER" || principal?.role === "DEPUTY");
  const [loadedIdentityKey, setLoadedIdentityKey] = useState<string>();
  const [pages, setPages] = useState<QueuePage[]>();
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [pending, setPending] = useState<DashboardQueue>();
  const epoch = useRef(0);
  const invalidateRequests = useCallback(() => {
    ++epoch.current;
  }, []);
  const reload = useCallback(async () => {
    const generation = ++epoch.current;
    setLoading(true);
    setPending(undefined);
    setError(undefined);
    const isCurrent = () => generation === epoch.current && identity.current === identityKey;
    if (canReconcile) {
      const reconciliation = await reconcilePackingUntilComplete(isCurrent);
      if (!isCurrent() || reconciliation.kind === "cancelled") return;
      if (reconciliation.kind === "error") {
        setError(
          `Packing review refresh failed: ${reviewError(reconciliation.error)}. Retry with Refresh workboard.`,
        );
        setLoading(false);
        return;
      }
    }
    const result = await getDashboard();
    if (!isCurrent()) return;
    setLoading(false);
    if (result.kind === "error") {
      setError(result.error.problem?.detail ?? result.error.message);
      return;
    }
    setPages(result.data.queues);
    setLoadedIdentityKey(identityKey);
  }, [identityKey, canReconcile]);
  useEffect(() => {
    const timer = window.setTimeout(() => void reload());
    return () => {
      window.clearTimeout(timer);
      invalidateRequests();
    };
  }, [reload, invalidateRequests, principal?.userId, principal?.organizationId, principal?.role]);
  async function more(page: QueuePage) {
    if (!page.nextCursor || pending) return;
    const generation = epoch.current;
    setPending(page.queue);
    setError(undefined);
    const result = await getDashboardPage(page.queue, page.nextCursor);
    if (generation !== epoch.current || identity.current !== identityKey) return;
    setPending(undefined);
    if (result.kind === "error") {
      setError(result.error.problem?.detail ?? result.error.message);
      return;
    }
    setPages((current) =>
      current?.map((item) =>
        item.queue === page.queue
          ? { ...result.data, items: [...item.items, ...result.data.items] }
          : item,
      ),
    );
  }
  const currentPages = loadedIdentityKey === identityKey ? pages : undefined;
  return (
    <>
      <PageHeading
        title="Workboard"
        description="The next handoffs, checks and equipment work for your organization."
        actions={
          <Button
            variant="outlined"
            disabled={loading}
            onClick={() => void reload()}
            sx={{ minHeight: 44 }}
          >
            Refresh workboard
          </Button>
        }
      />
      {error ? (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      ) : null}
      {loading ? (
        <Typography role="status" sx={{ mb: 2 }}>
          Loading workboard.
        </Typography>
      ) : null}
      {currentPages ? (
        <Stack spacing={4} aria-busy={loading}>
          {sections.map((section) => (
            <Box component="section" key={section.title} aria-label={section.title}>
              <Typography variant="h5" component="h2" sx={{ mb: 1.5 }}>
                {section.title}
              </Typography>
              <Paper variant="outlined">
                {section.queues.map((queue, index) => {
                  const page = currentPages.find((item) => item.queue === queue);
                  if (!page) return null;
                  return (
                    <Box key={queue} component="section" aria-label={labels[queue]}>
                      {index ? <Divider /> : null}
                      <Stack
                        direction="row"
                        spacing={1}
                        sx={{ px: 2, py: 1.5, alignItems: "baseline" }}
                      >
                        <Typography variant="h6" component="h3">
                          {labels[queue]}
                        </Typography>
                        <Typography color="text.secondary">({page.count})</Typography>
                      </Stack>
                      {page.items.length ? (
                        page.items.map((row) => (
                          <Stack
                            key={row.id}
                            direction={{ xs: "column", sm: "row" }}
                            spacing={1.5}
                            sx={{
                              px: 2,
                              py: 1.5,
                              borderTop: 1,
                              borderColor: "divider",
                              alignItems: { sm: "center" },
                            }}
                          >
                            <Box sx={{ flex: 1, minWidth: 0 }}>
                              <Typography sx={{ fontWeight: 600, overflowWrap: "anywhere" }}>
                                {row.label}
                                {row.code ? ` (${row.code})` : ""}
                              </Typography>
                              <Typography
                                variant="body2"
                                color="text.secondary"
                                sx={{ overflowWrap: "anywhere" }}
                              >
                                {row.reason}
                              </Typography>
                            </Box>
                            <Chip
                              size="small"
                              label={row.state?.replaceAll("_", " ")}
                              sx={{ alignSelf: { xs: "flex-start", sm: "center" } }}
                            />
                            <Box>
                              <Button
                                component={RouterLink}
                                to={row.actionPath}
                                sx={{ minHeight: 44, whiteSpace: "nowrap" }}
                              >
                                {row.actionLabel}
                              </Button>
                              {row.readOnly ? (
                                <Typography
                                  variant="caption"
                                  sx={{ display: "block" }}
                                  color="text.secondary"
                                >
                                  Read only
                                </Typography>
                              ) : null}
                            </Box>
                          </Stack>
                        ))
                      ) : (
                        <Typography color="text.secondary" sx={{ px: 2, pb: 2 }}>
                          {empty[queue]}
                        </Typography>
                      )}
                      {page.nextCursor ? (
                        <Button
                          disabled={pending !== undefined || loading}
                          onClick={() => void more(page)}
                          sx={{ m: 1, minHeight: 44 }}
                        >
                          {pending === queue
                            ? "Loading…"
                            : `Show more ${labels[queue].toLowerCase()}`}
                        </Button>
                      ) : null}
                    </Box>
                  );
                })}
              </Paper>
            </Box>
          ))}
        </Stack>
      ) : null}
    </>
  );
}
