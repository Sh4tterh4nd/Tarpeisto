import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Divider from "@mui/material/Divider";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { PageHeading } from "@tarpeisto/shared-ui";
import { useState } from "react";

const reports = [
  [
    "Inventory",
    "Models, physical units, current metadata and equipment locations.",
    "inventory.csv",
  ],
  [
    "Consumable balances",
    "Exact quantities, stock places, archive state and low-stock thresholds.",
    "consumable-balances.csv",
  ],
  [
    "Stock movements",
    "The complete movement ledger, including signed quantities and recorded actors.",
    "stock-movements.csv",
  ],
  [
    "Audit results",
    "Frozen expectations, observations, corrections, completion and appended review decisions.",
    "audits.csv",
  ],
] as const;

export function ReportsPage() {
  const [pending, setPending] = useState<string>();
  const [error, setError] = useState<string>();
  async function download(name: string) {
    setPending(name);
    setError(undefined);
    try {
      const response = await fetch(`/api/v1/reports/${name}`, { credentials: "same-origin" });
      if (!response.ok)
        throw new Error("The report could not be downloaded. Verify your sign-in and try again.");
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a");
      link.href = url;
      link.download = `tarpeisto-${name}`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "The report could not be downloaded.");
    } finally {
      setPending(undefined);
    }
  }
  return (
    <>
      <PageHeading
        title="Reports"
        description="Download organization-scoped records for reporting and spreadsheet use."
      />
      {error ? (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      ) : null}
      <Paper variant="outlined">
        {reports.map(([label, description, filename], index) => (
          <div key={filename}>
            {index ? <Divider /> : null}
            <Stack
              direction={{ xs: "column", sm: "row" }}
              spacing={2}
              sx={{ p: 2, alignItems: { sm: "center" }, justifyContent: "space-between" }}
            >
              <div>
                <Typography variant="h6">{label}</Typography>
                <Typography color="text.secondary">{description}</Typography>
              </div>
              <Button
                variant="outlined"
                disabled={pending !== undefined}
                onClick={() => void download(filename)}
                sx={{ minHeight: 44, flexShrink: 0 }}
              >
                {pending === filename ? "Preparing report…" : `Download ${label.toLowerCase()} CSV`}
              </Button>
            </Stack>
          </div>
        ))}
      </Paper>
      <Typography color="text.secondary" sx={{ mt: 2 }}>
        Archived records and retained history are included. Use database and media backups for
        disaster recovery.
      </Typography>
      <Typography color="text.secondary" sx={{ mt: 1 }}>
        Event manifests are available on each event. Equipment labels and P-touch CSV remain
        available from inventory.
      </Typography>
    </>
  );
}
