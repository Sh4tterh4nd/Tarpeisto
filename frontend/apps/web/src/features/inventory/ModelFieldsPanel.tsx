import { useCallback, useEffect, useState, type FormEvent } from "react";
import AddIcon from "@mui/icons-material/Add";
import ArchiveIcon from "@mui/icons-material/Archive";
import EditIcon from "@mui/icons-material/Edit";
import UnarchiveIcon from "@mui/icons-material/Unarchive";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import IconButton from "@mui/material/IconButton";
import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemText from "@mui/material/ListItemText";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import {
  createCustomField,
  createCustomFieldOption,
  changeCustomFieldDataType,
  errorMessage,
  listCustomFieldOptions,
  listCustomFields,
  renameCustomField,
  renameCustomFieldOption,
  reorderCustomField,
  reorderCustomFieldOption,
  setCustomFieldArchived,
  setCustomFieldOptionArchived,
  type CustomFieldOptionRecord,
  type CustomFieldRecord,
} from "./inventoryApi";

interface ModelFieldsPanelProps {
  assetModelId: string;
  canManage: boolean;
  onDefinitionsChanged?: () => void;
}

function FieldOptions({
  assetModelId,
  field,
  canManage,
}: {
  assetModelId: string;
  field: CustomFieldRecord;
  canManage: boolean;
}) {
  const [options, setOptions] = useState<CustomFieldOptionRecord[]>([]);
  const [value, setValue] = useState("");
  const [error, setError] = useState<string>();
  const [editing, setEditing] = useState<CustomFieldOptionRecord>();
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    const result = await listCustomFieldOptions(assetModelId, field.id);
    if (result.kind === "ok") {
      setOptions([...result.data].sort((left, right) => left.displayOrder - right.displayOrder));
      setError(undefined);
    } else setError(errorMessage(result.error));
  }, [assetModelId, field.id]);

  useEffect(() => {
    void (async () => {
      await load();
    })();
  }, [load]);

  async function addOption(event: FormEvent) {
    event.preventDefault();
    if (saving || !value.trim()) return;
    setSaving(true);
    setError(undefined);
    const result = await createCustomFieldOption(assetModelId, field.id, value.trim());
    setSaving(false);
    if (result.kind === "error") {
      setError(errorMessage(result.error));
      return;
    }
    setValue("");
    await load();
  }

  async function toggleOptionArchived(option: CustomFieldOptionRecord) {
    if (saving) return;
    setSaving(true);
    setError(undefined);
    const archiveError = await setCustomFieldOptionArchived(
      assetModelId,
      field.id,
      option.id,
      !option.archived,
    );
    setSaving(false);
    if (archiveError) {
      setError(errorMessage(archiveError));
      return;
    }
    await load();
  }

  async function saveOption(optionId: string, nextValue: string, displayOrder: number) {
    if (saving) return;
    setSaving(true);
    setError(undefined);
    const renamed = await renameCustomFieldOption(
      assetModelId,
      field.id,
      optionId,
      nextValue.trim(),
    );
    const reordered =
      renamed.kind === "ok"
        ? await reorderCustomFieldOption(assetModelId, field.id, optionId, displayOrder)
        : renamed;
    setSaving(false);
    if (reordered.kind === "error") {
      if (renamed.kind === "ok") await load();
      setError(errorMessage(reordered.error));
      return;
    }
    setEditing(undefined);
    await load();
  }

  const activeOptions = options.filter((option) => !option.archived);

  return (
    <Box sx={{ pl: { sm: 2 }, pb: 2 }}>
      {error ? <Alert severity="error">{error}</Alert> : null}
      <Stack direction="row" sx={{ gap: 1, flexWrap: "wrap", alignItems: "center" }}>
        {options.map((option) => (
          <Chip
            key={option.id}
            label={`${option.value}${option.archived ? " · archived" : ""}`}
            size="small"
            variant={option.archived ? "outlined" : "filled"}
            onClick={canManage ? () => setEditing(option) : undefined}
            onDelete={canManage ? () => void toggleOptionArchived(option) : undefined}
            deleteIcon={option.archived ? <UnarchiveIcon /> : <ArchiveIcon />}
          />
        ))}
        {activeOptions.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            Add at least one choice before this field can be completed.
          </Typography>
        ) : null}
      </Stack>
      {canManage && !field.archived ? (
        <Stack
          component="form"
          direction={{ xs: "column", sm: "row" }}
          spacing={1}
          onSubmit={addOption}
          sx={{ mt: 1, maxWidth: 440 }}
        >
          <TextField
            label="New choice"
            value={value}
            onChange={(event) => setValue(event.target.value)}
            size="small"
            fullWidth
          />
          <Button type="submit" disabled={saving || !value.trim()}>
            Add choice
          </Button>
        </Stack>
      ) : null}
      {editing ? (
        <OptionEditorDialog
          option={editing}
          onClose={() => setEditing(undefined)}
          onSave={saveOption}
          saving={saving}
          error={error}
        />
      ) : null}
    </Box>
  );
}

function OptionEditorDialog({
  option,
  onClose,
  onSave,
  saving,
  error,
}: {
  option: CustomFieldOptionRecord;
  onClose: () => void;
  onSave: (optionId: string, value: string, displayOrder: number) => Promise<void>;
  saving: boolean;
  error?: string;
}) {
  const [value, setValue] = useState(option.value);
  const [displayOrder, setDisplayOrder] = useState(String(option.displayOrder));
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <Stack
        component="form"
        onSubmit={(event) => {
          event.preventDefault();
          void onSave(option.id, value, Number(displayOrder));
        }}
      >
        <DialogTitle>Edit choice</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {error ? <Alert severity="error">{error}</Alert> : null}
            <TextField
              label="Choice"
              value={value}
              onChange={(event) => setValue(event.target.value)}
              required
              autoFocus
            />
            <TextField
              label="Display order"
              type="number"
              value={displayOrder}
              onChange={(event) => setDisplayOrder(event.target.value)}
              slotProps={{ htmlInput: { min: 0, step: 1 } }}
              required
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            type="submit"
            variant="contained"
            disabled={
              saving ||
              !value.trim() ||
              !Number.isInteger(Number(displayOrder)) ||
              Number(displayOrder) < 0
            }
          >
            Save choice
          </Button>
        </DialogActions>
      </Stack>
    </Dialog>
  );
}

export function ModelFieldsPanel({
  assetModelId,
  canManage,
  onDefinitionsChanged,
}: ModelFieldsPanelProps) {
  const [fields, setFields] = useState<CustomFieldRecord[]>();
  const [loadError, setLoadError] = useState<string>();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [name, setName] = useState("");
  const [dataType, setDataType] = useState<"STRING" | "DROPDOWN" | "DATE">("STRING");
  const [saving, setSaving] = useState(false);
  const [actionError, setActionError] = useState<string>();
  const [editing, setEditing] = useState<CustomFieldRecord>();

  const load = useCallback(async () => {
    const result = await listCustomFields(assetModelId);
    if (result.kind === "error") setLoadError(errorMessage(result.error));
    else {
      setFields([...result.data].sort((a, b) => a.displayOrder - b.displayOrder));
      setLoadError(undefined);
    }
  }, [assetModelId]);

  useEffect(() => {
    void (async () => {
      await load();
    })();
  }, [load]);

  async function create(event: FormEvent) {
    event.preventDefault();
    if (saving || !name.trim()) return;
    setSaving(true);
    setActionError(undefined);
    const result = await createCustomField(assetModelId, { name: name.trim(), dataType });
    setSaving(false);
    if (result.kind === "error") {
      setActionError(errorMessage(result.error));
      return;
    }
    setDialogOpen(false);
    setName("");
    await load();
    onDefinitionsChanged?.();
  }

  async function toggleArchived(field: CustomFieldRecord) {
    setActionError(undefined);
    const error = await setCustomFieldArchived(assetModelId, field.id, !field.archived);
    if (error) setActionError(errorMessage(error));
    else {
      await load();
      onDefinitionsChanged?.();
    }
  }

  async function saveField(
    fieldId: string,
    nextName: string,
    nextType: CustomFieldRecord["dataType"],
    displayOrder: number,
  ) {
    if (saving) return;
    setSaving(true);
    setActionError(undefined);
    const original = fields?.find((field) => field.id === fieldId);
    const renamed = await renameCustomField(assetModelId, fieldId, nextName.trim());
    const typed =
      renamed.kind === "ok" && original?.dataType !== nextType
        ? await changeCustomFieldDataType(assetModelId, fieldId, nextType)
        : renamed;
    const reordered =
      typed.kind === "ok" ? await reorderCustomField(assetModelId, fieldId, displayOrder) : typed;
    setSaving(false);
    if (reordered.kind === "error") {
      if (renamed.kind === "ok") {
        await load();
        onDefinitionsChanged?.();
      }
      setActionError(errorMessage(reordered.error));
      return;
    }
    setEditing(undefined);
    await load();
    onDefinitionsChanged?.();
  }

  return (
    <Paper variant="outlined" sx={{ overflow: "hidden" }}>
      <Stack
        direction="row"
        spacing={2}
        sx={{ alignItems: "center", justifyContent: "space-between", p: 2 }}
      >
        <Box>
          <Typography variant="h3">Unit fields</Typography>
          <Typography variant="body2" color="text.secondary">
            Required values live on each physical asset, never on this model.
          </Typography>
        </Box>
        {canManage ? (
          <Button startIcon={<AddIcon />} onClick={() => setDialogOpen(true)}>
            Add field
          </Button>
        ) : null}
      </Stack>
      {actionError ? (
        <Alert severity="error" onClose={() => setActionError(undefined)} sx={{ mx: 2, mb: 2 }}>
          {actionError}
        </Alert>
      ) : null}
      {loadError ? <Alert severity="error">{loadError}</Alert> : null}
      {!fields ? (
        <Stack direction="row" spacing={2} sx={{ p: 2, alignItems: "center" }}>
          <CircularProgress size={20} />
          <Typography role="status">Loading unit fields.</Typography>
        </Stack>
      ) : fields.length === 0 ? (
        <Typography color="text.secondary" sx={{ px: 2, pb: 3 }}>
          This model has no unit-specific fields.
        </Typography>
      ) : (
        <List disablePadding>
          {fields.map((field) => (
            <Box key={field.id} sx={{ borderTop: 1, borderColor: "divider" }}>
              <ListItem
                secondaryAction={
                  canManage ? (
                    <Stack direction="row">
                      <IconButton
                        aria-label={`Edit ${field.name}`}
                        onClick={() => setEditing(field)}
                      >
                        <EditIcon />
                      </IconButton>
                      <IconButton
                        aria-label={`${field.archived ? "Restore" : "Archive"} ${field.name}`}
                        onClick={() => void toggleArchived(field)}
                      >
                        {field.archived ? <UnarchiveIcon /> : <ArchiveIcon />}
                      </IconButton>
                    </Stack>
                  ) : null
                }
              >
                <ListItemText
                  primary={field.name}
                  secondary={`${field.dataType === "STRING" ? "Text" : field.dataType === "DATE" ? "Date" : "Dropdown"}${field.archived ? " · archived" : " · required"}`}
                />
              </ListItem>
              {field.dataType === "DROPDOWN" ? (
                <FieldOptions assetModelId={assetModelId} field={field} canManage={canManage} />
              ) : null}
            </Box>
          ))}
        </List>
      )}

      {dialogOpen ? (
        <Dialog open onClose={() => setDialogOpen(false)} fullWidth maxWidth="xs">
          <Stack component="form" onSubmit={create}>
            <DialogTitle>New unit field</DialogTitle>
            <DialogContent>
              <Stack spacing={2} sx={{ pt: 1 }}>
                {actionError ? <Alert severity="error">{actionError}</Alert> : null}
                <TextField
                  label="Field name"
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                  required
                  autoFocus
                />
                <TextField
                  select
                  label="Value type"
                  value={dataType}
                  onChange={(event) =>
                    setDataType(event.target.value as "STRING" | "DROPDOWN" | "DATE")
                  }
                >
                  <MenuItem value="STRING">Text</MenuItem>
                  <MenuItem value="DROPDOWN">Dropdown</MenuItem>
                  <MenuItem value="DATE">Date</MenuItem>
                </TextField>
              </Stack>
            </DialogContent>
            <DialogActions>
              <Button onClick={() => setDialogOpen(false)}>Cancel</Button>
              <Button type="submit" variant="contained" disabled={saving || !name.trim()}>
                Add field
              </Button>
            </DialogActions>
          </Stack>
        </Dialog>
      ) : null}
      {editing ? (
        <FieldEditorDialog
          field={editing}
          onClose={() => setEditing(undefined)}
          onSave={saveField}
          saving={saving}
          error={actionError}
        />
      ) : null}
    </Paper>
  );
}

function FieldEditorDialog({
  field,
  onClose,
  onSave,
  saving,
  error,
}: {
  field: CustomFieldRecord;
  onClose: () => void;
  onSave: (
    fieldId: string,
    name: string,
    dataType: CustomFieldRecord["dataType"],
    displayOrder: number,
  ) => Promise<void>;
  saving: boolean;
  error?: string;
}) {
  const [name, setName] = useState(field.name);
  const [dataType, setDataType] = useState(field.dataType);
  const [displayOrder, setDisplayOrder] = useState(String(field.displayOrder));
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <Stack
        component="form"
        onSubmit={(event) => {
          event.preventDefault();
          void onSave(field.id, name, dataType, Number(displayOrder));
        }}
      >
        <DialogTitle>Edit unit field</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {error ? <Alert severity="error">{error}</Alert> : null}
            <TextField
              label="Field name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              required
              autoFocus
            />
            <TextField
              select
              label="Value type"
              value={dataType}
              onChange={(event) => setDataType(event.target.value as CustomFieldRecord["dataType"])}
            >
              <MenuItem value="STRING">Text</MenuItem>
              <MenuItem value="DROPDOWN">Dropdown</MenuItem>
              <MenuItem value="DATE">Date</MenuItem>
            </TextField>
            <TextField
              label="Display order"
              type="number"
              value={displayOrder}
              onChange={(event) => setDisplayOrder(event.target.value)}
              slotProps={{ htmlInput: { min: 0, step: 1 } }}
              required
            />
            <Alert severity="info">
              The server protects datatype changes when values already exist.
            </Alert>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            type="submit"
            variant="contained"
            disabled={
              saving ||
              !name.trim() ||
              !Number.isInteger(Number(displayOrder)) ||
              Number(displayOrder) < 0
            }
          >
            Save field
          </Button>
        </DialogActions>
      </Stack>
    </Dialog>
  );
}
