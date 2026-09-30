import { useCallback, useEffect, useState, type FormEvent } from "react";
import AddLocationAltOutlinedIcon from "@mui/icons-material/AddLocationAltOutlined";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import FormControlLabel from "@mui/material/FormControlLabel";
import Switch from "@mui/material/Switch";
import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemText from "@mui/material/ListItemText";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { PageHeading } from "@tarpeisto/shared-ui";
import { useSession } from "../identity/useSession";
import {
  createLocation,
  errorMessage,
  listLocations,
  listLocationStock,
  setLocationArchived,
  updateLocation,
  type LocationRecord,
} from "./inventoryApi";

function LocationStock({ locationId }: { locationId: string }) {
  const [text, setText] = useState("");
  useEffect(() => {
    void (async () => {
      const result = await listLocationStock(locationId);
      if (result.kind === "ok" && result.data.length > 0)
        setText(
          result.data
            .map(
              (balance) =>
                `${balance.quantity} ${balance.stockUnitLabel} ${balance.assetModelName}`,
            )
            .join(", "),
        );
    })();
  }, [locationId]);
  return text ? (
    <Typography variant="body2" color="text.secondary">
      Stock: {text}
    </Typography>
  ) : null;
}

export function LocationsPage() {
  const { role } = useSession();
  const canManage = role === "OWNER" || role === "DEPUTY";
  const [includeArchived, setIncludeArchived] = useState(false);
  const [locations, setLocations] = useState<LocationRecord[]>();
  const [error, setError] = useState<string>();
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<LocationRecord>();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [historicalParent, setHistoricalParent] = useState<LocationRecord>();
  const [parentLocationId, setParentLocationId] = useState("");
  const load = useCallback(
    async (isCurrent: () => boolean = () => true) => {
      const result = await listLocations(includeArchived);
      if (!isCurrent()) return;
      if (result.kind === "error") setError(errorMessage(result.error));
      else {
        setLocations(result.data);
        setError(undefined);
      }
    },
    [includeArchived],
  );
  useEffect(() => {
    let stale = false;
    void (async () => {
      await load(() => !stale);
    })();
    return () => {
      stale = true;
    };
  }, [load]);
  async function create(event: FormEvent) {
    event.preventDefault();
    try {
      const input = {
        name,
        description: description || undefined,
        parentLocationId: parentLocationId || undefined,
      };
      const result = editing
        ? await updateLocation(editing.id, { ...input, expectedVersion: editing.version })
        : await createLocation(input);
      if (result.kind === "error") {
        setError(errorMessage(result.error));
        return;
      }
      setOpen(false);
      setName("");
      setDescription("");
      setParentLocationId("");
      setEditing(undefined);
      await load();
    } catch {
      setError("Could not create location.");
    }
  }
  function edit(location: LocationRecord) {
    setEditing(location);
    setName(location.name);
    setDescription(location.description ?? "");
    setParentLocationId(location.parentLocationId ?? "");
    setHistoricalParent(undefined);
    if (
      location.parentLocationId &&
      !locations?.some((parent) => parent.id === location.parentLocationId)
    ) {
      void listLocations(true).then((result) => {
        if (result.kind === "ok")
          setHistoricalParent(
            result.data.find((parent) => parent.id === location.parentLocationId),
          );
      });
    }
    setOpen(true);
  }
  async function toggleArchive(location: LocationRecord) {
    const result = await setLocationArchived(location.id, !location.archived, location.version);
    if (result) setError(errorMessage(result));
    else await load();
  }
  return (
    <Stack spacing={3}>
      <FormControlLabel
        label="Include archived"
        control={
          <Switch
            checked={includeArchived}
            onChange={(event) => setIncludeArchived(event.target.checked)}
          />
        }
      />
      <PageHeading
        title="Locations"
        description="Physical places, arranged as a breadcrumb hierarchy."
        actions={
          canManage ? (
            <Button
              variant="contained"
              startIcon={<AddLocationAltOutlinedIcon />}
              onClick={() => {
                setEditing(undefined);
                setName("");
                setDescription("");
                setParentLocationId("");
                setOpen(true);
              }}
            >
              Add location
            </Button>
          ) : undefined
        }
      />
      {error ? (
        <Alert severity="error" onClose={() => setError(undefined)}>
          {error}
        </Alert>
      ) : null}
      {!locations ? (
        <Stack direction="row" spacing={2} sx={{ alignItems: "center", py: 5 }}>
          <CircularProgress size={24} />
          <Typography role="status">Loading locations.</Typography>
        </Stack>
      ) : (
        <Paper variant="outlined">
          <List disablePadding>
            {locations.length === 0 ? (
              <Box sx={{ p: 3 }}>
                <Typography color="text.secondary">
                  No locations yet. Add a top-level place such as HQ.
                </Typography>
              </Box>
            ) : (
              locations.map((location) => (
                <ListItem
                  key={location.id}
                  divider
                  sx={{ alignItems: "flex-start", opacity: location.archived ? 0.6 : 1 }}
                >
                  <ListItemText
                    primary={location.name}
                    secondary={
                      location.effectivePath +
                      (location.description ? ` - ${location.description}` : "")
                    }
                  />
                  <LocationStock locationId={location.id} />
                  {canManage ? (
                    <Stack direction="row">
                      <Button size="small" onClick={() => edit(location)}>
                        Edit
                      </Button>
                      <Button
                        size="small"
                        color={location.archived ? "primary" : "warning"}
                        onClick={() => void toggleArchive(location)}
                      >
                        {location.archived ? "Restore" : "Archive"}
                      </Button>
                    </Stack>
                  ) : null}
                </ListItem>
              ))
            )}
          </List>
        </Paper>
      )}
      <Dialog
        open={open}
        onClose={() => {
          setOpen(false);
          setEditing(undefined);
          setName("");
          setDescription("");
          setParentLocationId("");
        }}
        fullWidth
        maxWidth="sm"
      >
        <Stack component="form" onSubmit={create}>
          <DialogTitle>{editing ? "Edit location" : "Add location"}</DialogTitle>
          <DialogContent>
            <Stack spacing={2} sx={{ pt: 1 }}>
              <TextField
                label="Name"
                required
                autoFocus
                value={name}
                onChange={(event) => setName(event.target.value)}
              />
              <TextField
                label="Description"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                multiline
                minRows={2}
              />
              <TextField
                select
                label="Parent location"
                value={parentLocationId}
                onChange={(event) => setParentLocationId(event.target.value)}
              >
                <MenuItem value="">Top level</MenuItem>
                {editing?.parentLocationId === parentLocationId &&
                parentLocationId &&
                !locations?.some((parent) => parent.id === parentLocationId) ? (
                  <MenuItem value={parentLocationId}>
                    {historicalParent?.effectivePath
                      ? `${historicalParent.effectivePath} (archived)`
                      : "Loading current parent..."}
                  </MenuItem>
                ) : null}
                {locations
                  ?.filter(
                    (location) => !location.archived || location.id === editing?.parentLocationId,
                  )
                  .filter((location) => location.id !== editing?.id)
                  .map((location) => (
                    <MenuItem key={location.id} value={location.id}>
                      {location.effectivePath}
                    </MenuItem>
                  ))}
              </TextField>
            </Stack>
          </DialogContent>
          <DialogActions>
            <Button
              onClick={() => {
                setOpen(false);
                setEditing(undefined);
              }}
            >
              Cancel
            </Button>
            <Button type="submit" variant="contained">
              {editing ? "Save location" : "Create location"}
            </Button>
          </DialogActions>
        </Stack>
      </Dialog>
    </Stack>
  );
}
