import { useCallback, useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { Link as RouterLink } from "react-router-dom";
import { PageHeading } from "@tarpeisto/shared-ui";
import { useSession } from "../identity/useSession";
import { listAssignedTasks, type AssignedAuditTask } from "./temporaryAccessApi";

export function VolunteerDashboardPage() {
  const { principal } = useSession();
  const [tasks, setTasks] = useState<AssignedAuditTask[]>();
  const [error, setError] = useState<string>();
  const load = useCallback(async () => {
    const result = await listAssignedTasks();
    if (result.kind === "ok") {
      setTasks(result.data);
      setError(undefined);
    } else setError(result.error.problem?.detail ?? result.error.message);
  }, []);
  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);
  return (
    <Stack spacing={2}>
      <PageHeading
        title="Your assigned audits"
        description={`Welcome, ${principal?.displayName ?? "volunteer"}.`}
      />
      <Alert severity="info">
        Access expires{" "}
        {principal?.temporaryAccess?.expiresAt
          ? new Date(principal.temporaryAccess.expiresAt).toLocaleString()
          : "after 24 hours"}
        . Reopening this page does not extend it.
      </Alert>
      {error ? <Alert severity="error">{error}</Alert> : null}
      <Button onClick={() => void load()} sx={{ alignSelf: "start" }}>
        Refresh assigned tasks
      </Button>
      {tasks?.length === 0 ? (
        <Alert severity="info">
          Waiting for event returns. Assigned audits will appear when containers are checked in.
        </Alert>
      ) : null}
      {tasks?.map((task) => (
        <Paper key={task.taskId} sx={{ p: 2 }}>
          <Stack spacing={1}>
            <Typography variant="h6">{task.displayName}</Typography>
            <Typography>
              {task.publicCode} · {task.state.replaceAll("_", " ")}
            </Typography>
            {task.blockingReasons.map((reason) => (
              <Typography key={reason}>{reason}</Typography>
            ))}
            <Button
              component={RouterLink}
              to={`/audits/tasks/${task.taskId}`}
              variant="outlined"
              sx={{ alignSelf: "start" }}
            >
              Open audit
            </Button>
          </Stack>
        </Paper>
      ))}
    </Stack>
  );
}
