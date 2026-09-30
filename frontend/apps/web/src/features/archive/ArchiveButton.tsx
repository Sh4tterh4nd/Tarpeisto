import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Stack from "@mui/material/Stack";
import { useState } from "react";
import { changeArchive, type ArchiveKind } from "./archiveApi";

export function ArchiveButton({
  kind,
  id,
  archived,
  version,
  onChanged,
  label = "record",
}: {
  kind: ArchiveKind;
  id: string;
  archived?: boolean;
  version: number;
  onChanged: () => void | Promise<void>;
  label?: string;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string>();
  async function change() {
    setPending(true);
    setError(undefined);
    const failure = await changeArchive(kind, id, !archived, version);
    if (failure) setError(failure.problem?.detail ?? failure.message);
    else await onChanged();
    setPending(false);
  }
  return (
    <Stack spacing={1}>
      <Button
        color={archived ? "primary" : "warning"}
        disabled={pending}
        onClick={() => void change()}
        sx={{ minHeight: 44 }}
      >
        {pending ? "Saving…" : `${archived ? "Restore" : "Archive"} ${label}`}
      </Button>
      {error ? <Alert severity="error">{error}</Alert> : null}
    </Stack>
  );
}
