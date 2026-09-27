import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import AddIcon from "@mui/icons-material/Add";
import ArchiveIcon from "@mui/icons-material/Archive";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import Accordion from "@mui/material/Accordion";
import AccordionDetails from "@mui/material/AccordionDetails";
import AccordionSummary from "@mui/material/AccordionSummary";
import Autocomplete from "@mui/material/Autocomplete";
import EditIcon from "@mui/icons-material/Edit";
import RestoreIcon from "@mui/icons-material/Restore";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Divider from "@mui/material/Divider";
import FormControlLabel from "@mui/material/FormControlLabel";
import Checkbox from "@mui/material/Checkbox";
import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemText from "@mui/material/ListItemText";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { Link as RouterLink } from "react-router-dom";
import { downloadPackingSheet, savePackingSheet } from "./packingSheetApi";
import {
  addPackingRequirement,
  addPackingTemplateRequirement,
  applyPackingTemplate,
  archivePackingRequirement,
  createPackingTemplate,
  errorMessage,
  getAsset,
  listAssetModels,
  searchAssets,
  listPackingRequirements,
  listPackingTemplates,
  previewPacking,
  restorePackingRequirement,
  setPackingTemplateArchived,
  setPackingTemplateRequirementArchived,
  updatePackingRequirement,
  updatePackingTemplate,
  updatePackingTemplateRequirement,
  type AssetModelRecord,
  type AssetSearchRecord,
  type PackingRequirementInput,
  type PackingRequirementRecord,
  type PackingTemplateRecord,
  type PackingPreviewRecord,
} from "./inventoryApi";

const groups = ["SPECIFIC_ASSET", "MODEL_QUANTITY", "CONSUMABLE_QUANTITY"] as const;
const labels = {
  SPECIFIC_ASSET: "Exact assets",
  MODEL_QUANTITY: "Interchangeable serialized",
  CONSUMABLE_QUANTITY: "Consumables",
};
type RequirementEditor =
  | { scope: "container"; requirement?: PackingRequirementRecord }
  | { scope: "template"; templateId: string; requirement?: PackingRequirementRecord };

function modelLabel(models: AssetModelRecord[], id?: string) {
  return models.find((candidate) => candidate.id === id)?.name ?? id ?? "Unspecified model";
}

function requirementLabel(requirement: PackingRequirementRecord, models: AssetModelRecord[]) {
  return requirement.type === "SPECIFIC_ASSET"
    ? `Exact asset ${requirement.specificAssetId}`
    : modelLabel(models, requirement.assetModelId);
}

function makeRequirementInput(
  type: PackingRequirementInput["type"],
  assetModelId: string,
  specificAssetReference: string,
  quantity: string,
  selectedAsset?: AssetSearchRecord,
  assignToContainer = false,
): PackingRequirementInput {
  return {
    type,
    assetModelId: type === "SPECIFIC_ASSET" ? undefined : assetModelId || undefined,
    specificAssetId: type === "SPECIFIC_ASSET" ? selectedAsset?.id : undefined,
    specificAssetReference:
      type === "SPECIFIC_ASSET" && !selectedAsset
        ? specificAssetReference.trim() || undefined
        : undefined,
    // Exact requirements are always a single pinned asset, even after a form-kind change.
    requiredQuantity: type === "SPECIFIC_ASSET" ? 1 : Number(quantity),
    assignToContainer: type === "SPECIFIC_ASSET" && assignToContainer,
    expectedAssetVersion:
      type === "SPECIFIC_ASSET" && assignToContainer ? selectedAsset?.placementVersion : undefined,
  };
}

function observedConsumableQuantities(observations: Record<string, string>) {
  return Object.fromEntries(
    Object.entries(observations)
      .filter(([, value]) => value.trim() !== "")
      .map(([id, value]) => [id, Number(value)]),
  );
}

export function PackingPanel({
  containerAssetId,
  canManage,
  onContentsChanged,
}: {
  containerAssetId: string;
  canManage: boolean;
  onContentsChanged?: () => void;
}) {
  const [requirements, setRequirements] = useState<PackingRequirementRecord[]>();
  const [preview, setPreview] = useState<PackingPreviewRecord>();
  const [templates, setTemplates] = useState<PackingTemplateRecord[]>([]);
  const [models, setModels] = useState<AssetModelRecord[]>([]);
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [templateId, setTemplateId] = useState("");
  const [observations, setObservations] = useState<Record<string, string>>({});
  const observationsRef = useRef<Record<string, string>>({});
  const [templateLibraryOpen, setTemplateLibraryOpen] = useState(false);
  const [templateEditor, setTemplateEditor] = useState<PackingTemplateRecord | null>();
  const [templateName, setTemplateName] = useState("");
  const [templateDescription, setTemplateDescription] = useState("");
  const [requirementEditor, setRequirementEditor] = useState<RequirementEditor>();
  const [affectedBookingIds, setAffectedBookingIds] = useState<string[]>();
  const [pendingArchive, setPendingArchive] = useState<PackingRequirementRecord>();
  const [type, setType] = useState<PackingRequirementInput["type"]>("MODEL_QUANTITY");
  const [assetModelId, setAssetModelId] = useState("");
  const [specificAssetReference, setSpecificAssetReference] = useState("");
  const [selectedAsset, setSelectedAsset] = useState<AssetSearchRecord | null>(null);
  const [assetOptions, setAssetOptions] = useState<AssetSearchRecord[]>([]);
  const [assetOptionsQuery, setAssetOptionsQuery] = useState<string>();
  const [assetSearchError, setAssetSearchError] = useState<string>();
  const [assetQuery, setAssetQuery] = useState("");
  const selectionRequest = useRef(0);
  const [assignToContainer, setAssignToContainer] = useState(false);
  const [quantity, setQuantity] = useState("1");
  const [packingSheetBusy, setPackingSheetBusy] = useState(false);
  const [packingSheetError, setPackingSheetError] = useState<string>();

  const activeRequirements = requirements?.filter((requirement) => !requirement.archived) ?? [];
  const archivedRequirements = requirements?.filter((requirement) => requirement.archived) ?? [];
  const activeTemplates = templates.filter((template) => !template.archived);
  const visibleModels = useMemo(
    () =>
      models.filter((model) =>
        type === "CONSUMABLE_QUANTITY"
          ? model.trackingMode === "QUANTITY_STOCK"
          : model.trackingMode === "SERIALIZED_ASSET",
      ),
    [models, type],
  );

  const reload = useCallback(
    async (isCurrent: () => boolean = () => true) => {
      const [packed, templateResult, modelResult] = await Promise.all([
        listPackingRequirements(containerAssetId),
        listPackingTemplates(),
        listAssetModels(),
      ]);
      if (!isCurrent()) return;
      if (packed.kind === "error") {
        setError(errorMessage(packed.error));
        return;
      }
      const activeConsumableRequirementIds = new Set(
        packed.data
          .filter(
            (requirement) => !requirement.archived && requirement.type === "CONSUMABLE_QUANTITY",
          )
          .map((requirement) => requirement.id),
      );
      const previousObservations = observationsRef.current;
      const nextObservations = Object.fromEntries(
        Object.entries(previousObservations).filter(([id]) =>
          activeConsumableRequirementIds.has(id),
        ),
      );
      const observationsChanged =
        Object.keys(nextObservations).length !== Object.keys(previousObservations).length ||
        Object.entries(nextObservations).some(([id, value]) => previousObservations[id] !== value);
      observationsRef.current = nextObservations;
      const previewResult = await previewPacking(
        containerAssetId,
        observedConsumableQuantities(nextObservations),
      );
      if (!isCurrent()) return;
      if (observationsChanged) setObservations(nextObservations);
      setRequirements(packed.data);
      if (templateResult.kind === "ok") setTemplates(templateResult.data);
      if (modelResult.kind === "ok") setModels(modelResult.data.filter((model) => !model.archived));
      if (previewResult.kind === "ok") setPreview(previewResult.data);
    },
    [containerAssetId],
  );

  useEffect(() => {
    let stale = false;
    void (async () => {
      await reload(() => !stale);
    })();
    return () => {
      stale = true;
    };
  }, [reload]);

  useEffect(() => {
    if (type !== "SPECIFIC_ASSET" || !requirementEditor) {
      return;
    }
    let stale = false;
    const timer = window.setTimeout(() => {
      void (async () => {
        const result = await searchAssets({
          query: assetQuery.trim() || undefined,
          sort: "name",
          direction: "asc",
          limit: 20,
        });
        if (stale) return;
        setAssetOptionsQuery(assetQuery);
        if (result.kind === "ok") {
          setAssetOptions(result.data.items.filter((asset) => asset.id !== containerAssetId));
          setAssetSearchError(undefined);
        } else {
          setAssetOptions([]);
          setAssetSearchError(errorMessage(result.error));
        }
      })();
    }, 250);
    return () => {
      stale = true;
      window.clearTimeout(timer);
    };
  }, [assetQuery, containerAssetId, requirementEditor, type]);

  useEffect(() => {
    const id = requirementEditor?.requirement?.specificAssetId;
    if (!id || type !== "SPECIFIC_ASSET") return;
    const request = ++selectionRequest.current;
    let stale = false;
    void (async () => {
      const asset = await getAsset(id);
      if (stale || request !== selectionRequest.current || asset.kind !== "ok") return;
      const result = await searchAssets({
        query: asset.data.publicCode,
        includeInactive: true,
        limit: 1,
      });
      if (stale || request !== selectionRequest.current || result.kind !== "ok") return;
      const existing = result.data.items.find((item) => item.id === id);
      if (existing) setSelectedAsset(existing);
    })();
    return () => {
      stale = true;
    };
  }, [requirementEditor, type]);

  async function run(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true);
    setError(undefined);
    try {
      await action();
    } finally {
      setBusy(false);
    }
  }

  async function downloadSheet() {
    if (packingSheetBusy) return;
    setPackingSheetBusy(true);
    setPackingSheetError(undefined);
    const result = await downloadPackingSheet(containerAssetId);
    setPackingSheetBusy(false);
    if (result.kind === "error") {
      setPackingSheetError(errorMessage(result.error));
      return;
    }
    savePackingSheet(result.data, containerAssetId);
  }

  function openRequirementEditor(editor: RequirementEditor) {
    setError(undefined);
    const requirement = editor.requirement;
    setRequirementEditor(editor);
    setType((requirement?.type as PackingRequirementInput["type"]) ?? "MODEL_QUANTITY");
    setAssetModelId(requirement?.assetModelId ?? "");
    // The backend accepts either public code or UUID. Existing requirements expose the latter.
    setSpecificAssetReference(requirement?.specificAssetId ?? "");
    setSelectedAsset(null);
    setAssetOptions([]);
    setAssetOptionsQuery(undefined);
    setAssetSearchError(undefined);
    setAssetQuery("");
    setAssignToContainer(false);
    setQuantity(requirement?.requiredQuantity?.toString() ?? "1");
  }

  async function saveRequirement(event: FormEvent) {
    event.preventDefault();
    if (!requirementEditor) return;
    await run(async () => {
      const input = makeRequirementInput(
        type,
        assetModelId,
        specificAssetReference,
        quantity,
        selectedAsset ?? undefined,
        requirementEditor.scope === "container" && assignToContainer,
      );
      if (requirementEditor.scope === "container") {
        const result = requirementEditor.requirement
          ? await updatePackingRequirement(requirementEditor.requirement.id, {
              expectedVersion: requirementEditor.requirement.version,
              requirement: input,
            })
          : await addPackingRequirement(containerAssetId, input);
        if (result.kind === "error") {
          setError(errorMessage(result.error));
          return;
        }
      } else {
        const result = requirementEditor.requirement
          ? await updatePackingTemplateRequirement(requirementEditor.requirement.id, {
              expectedVersion: requirementEditor.requirement.version,
              requirement: input,
            })
          : await addPackingTemplateRequirement(requirementEditor.templateId, input);
        if (result.kind === "error") {
          setError(errorMessage(result.error));
          return;
        }
      }
      setRequirementEditor(undefined);
      await reload();
      if (assignToContainer && requirementEditor.scope === "container") onContentsChanged?.();
    });
  }

  async function toggleContainerRequirement(requirement: PackingRequirementRecord) {
    await run(async () => {
      if (requirement.archived) {
        const result = await restorePackingRequirement(requirement.id, requirement.version);
        if (result.kind === "error") setError(errorMessage(result.error));
      } else {
        const failure = await archivePackingRequirement(requirement.id, requirement.version);
        if (failure?.errorCode === "PACKING_REMOVAL_CONFIRMATION_REQUIRED") {
          const problem = failure.problem as typeof failure.problem & {
            affectedBookingIds?: string[];
          };
          setAffectedBookingIds(problem?.affectedBookingIds ?? []);
          setPendingArchive(requirement);
          return;
        }
        if (failure) setError(errorMessage(failure));
      }
      await reload();
    });
  }

  async function toggleTemplateRequirement(requirement: PackingRequirementRecord) {
    await run(async () => {
      const result = await setPackingTemplateRequirementArchived(
        requirement.id,
        requirement.version,
        requirement.archived ? "restore" : "archive",
      );
      if (result.kind === "error") setError(errorMessage(result.error));
      await reload();
    });
  }

  async function refreshPreview() {
    await run(async () => {
      const result = await previewPacking(
        containerAssetId,
        observedConsumableQuantities(observationsRef.current),
      );
      if (result.kind === "ok") setPreview(result.data);
      else setError(errorMessage(result.error));
    });
  }

  async function applyTemplate() {
    if (!templateId) return;
    await run(async () => {
      const result = await applyPackingTemplate(containerAssetId, templateId);
      if (result.kind === "error") setError(errorMessage(result.error));
      else await reload();
    });
  }

  function openTemplateEditor(template?: PackingTemplateRecord) {
    setTemplateEditor(template ?? null);
    setTemplateName(template?.name ?? "");
    setTemplateDescription(template?.description ?? "");
  }

  async function saveTemplate(event: FormEvent) {
    event.preventDefault();
    if (!templateName.trim()) return;
    await run(async () => {
      const result = templateEditor
        ? await updatePackingTemplate(templateEditor.id, {
            expectedVersion: templateEditor.version,
            name: templateName.trim(),
            description: templateDescription.trim() || undefined,
          })
        : await createPackingTemplate({
            name: templateName.trim(),
            description: templateDescription.trim() || undefined,
          });
      if (result.kind === "error") {
        setError(errorMessage(result.error));
        return;
      }
      setTemplateId(result.data.id);
      setTemplateEditor(undefined);
      await reload();
    });
  }

  async function toggleTemplate(template: PackingTemplateRecord) {
    await run(async () => {
      const result = await setPackingTemplateArchived(
        template.id,
        template.version,
        template.archived ? "restore" : "archive",
      );
      if (result.kind === "error") setError(errorMessage(result.error));
      await reload();
    });
  }

  function requirementState(requirement: PackingRequirementRecord) {
    if (!preview) return "Loading";
    return preview.missingRequirementIds.includes(requirement.id) ? "Missing" : "Complete";
  }

  return (
    <Accordion defaultExpanded={false} variant="outlined" disableGutters>
      <AccordionSummary expandIcon={<ExpandMoreIcon />} aria-controls="packing-sheet-panel-content">
        <Stack>
          <Typography variant="h3">Packing sheet</Typography>
          <Typography variant="body2" color="text.secondary">
            Requirements and printable container contents sheet.
          </Typography>
        </Stack>
      </AccordionSummary>
      <AccordionDetails id="packing-sheet-panel-content">
        <Stack spacing={2}>
          <Stack
            direction={{ xs: "column", sm: "row" }}
            spacing={1}
            sx={{ justifyContent: "space-between" }}
          >
            <div>
              <Typography variant="h4">Packing requirements</Typography>
              <Typography color="text.secondary">
                This container keeps its own requirements. Templates are copied, never live-linked.
              </Typography>
            </div>
            <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap" }}>
              <Button onClick={() => void downloadSheet()} disabled={packingSheetBusy}>
                {packingSheetBusy ? "Preparing sheet." : "Download packing sheet"}
              </Button>
              {canManage ? (
                <>
                  <Button onClick={() => setTemplateLibraryOpen(true)}>Manage templates</Button>
                  <Button
                    startIcon={<AddIcon />}
                    onClick={() => openRequirementEditor({ scope: "container" })}
                  >
                    Add requirement
                  </Button>
                </>
              ) : null}
            </Stack>
          </Stack>
          {error ? <Alert severity="error">{error}</Alert> : null}
          {packingSheetError ? (
            <Alert
              severity="error"
              action={
                <Button color="inherit" size="small" onClick={() => void downloadSheet()}>
                  Retry
                </Button>
              }
            >
              Could not download the packing sheet: {packingSheetError}
            </Alert>
          ) : null}
          {preview ? (
            <Alert severity={preview.complete ? "success" : "warning"}>
              {preview.complete
                ? "Packing is complete."
                : `Packing needs attention: ${preview.missingRequirementIds.length} missing requirement${preview.missingRequirementIds.length === 1 ? "" : "s"}.`}
              {preview.extraAssetIds.length ? ` Extra: ${preview.extraAssetIds.join(", ")}.` : ""}
              {preview.misplacedAssetIds.length
                ? ` Misplaced: ${preview.misplacedAssetIds.join(", ")}.`
                : ""}
            </Alert>
          ) : null}
          {canManage && activeTemplates.length ? (
            <Stack direction={{ xs: "column", sm: "row" }} spacing={1}>
              <TextField
                select
                label="Copy template onto this container"
                value={templateId}
                onChange={(event) => setTemplateId(event.target.value)}
                sx={{ minWidth: 280 }}
              >
                <MenuItem value="">Select a template</MenuItem>
                {activeTemplates.map((template) => (
                  <MenuItem key={template.id} value={template.id}>
                    {template.name}
                  </MenuItem>
                ))}
              </TextField>
              <Button onClick={() => void applyTemplate()} disabled={!templateId || busy}>
                Copy requirements
              </Button>
            </Stack>
          ) : null}
          {!requirements ? (
            <Typography color="text.secondary">Loading packing requirements.</Typography>
          ) : (
            <Stack spacing={1.5}>
              {groups.map((group) => {
                const inGroup = activeRequirements.filter(
                  (requirement) => requirement.type === group,
                );
                return (
                  <section key={group} aria-label={labels[group]}>
                    <Typography variant="h4">{labels[group]}</Typography>
                    {inGroup.length === 0 ? (
                      <Typography color="text.secondary">No requirements.</Typography>
                    ) : (
                      <List dense disablePadding>
                        {inGroup.map((requirement) => (
                          <ListItem
                            key={requirement.id}
                            secondaryAction={
                              canManage ? (
                                <Stack direction="row" spacing={0.5}>
                                  <Button
                                    size="small"
                                    startIcon={<EditIcon />}
                                    disabled={busy}
                                    onClick={() =>
                                      openRequirementEditor({ scope: "container", requirement })
                                    }
                                  >
                                    Edit
                                  </Button>
                                  <Button
                                    size="small"
                                    color="warning"
                                    startIcon={<ArchiveIcon />}
                                    disabled={busy}
                                    onClick={() => void toggleContainerRequirement(requirement)}
                                  >
                                    Archive
                                  </Button>
                                </Stack>
                              ) : undefined
                            }
                          >
                            <ListItemText
                              primary={requirementLabel(requirement, models)}
                              secondary={`Required: ${requirement.requiredQuantity} · ${requirementState(requirement)}`}
                            />
                          </ListItem>
                        ))}
                      </List>
                    )}
                  </section>
                );
              })}
              {preview?.consumables.length ? (
                <section aria-label="Observed consumables">
                  <Typography variant="h4">Observed consumables</Typography>
                  <Stack spacing={1} sx={{ mt: 1 }}>
                    {preview.consumables.map((status) => (
                      <TextField
                        key={status.requirementId}
                        label={`Observed ${modelLabel(models, status.assetModelId)}`}
                        type="number"
                        value={observations[status.requirementId] ?? status.observedQuantity}
                        onChange={(event) => {
                          const nextObservations = {
                            ...observationsRef.current,
                            [status.requirementId]: event.target.value,
                          };
                          observationsRef.current = nextObservations;
                          setObservations(nextObservations);
                        }}
                        helperText={`Required ${status.requiredQuantity} · ${status.satisfied ? "Complete" : "Missing"}`}
                        slotProps={{ htmlInput: { min: 0, step: 0.001 } }}
                      />
                    ))}
                    <Button
                      variant="outlined"
                      onClick={() => void refreshPreview()}
                      disabled={busy}
                    >
                      Refresh preview
                    </Button>
                  </Stack>
                </section>
              ) : null}
              {archivedRequirements.length ? (
                <section aria-label="Archived requirements">
                  <Typography variant="h4">Archived requirements</Typography>
                  <List dense disablePadding>
                    {archivedRequirements.map((requirement) => (
                      <ListItem
                        key={requirement.id}
                        secondaryAction={
                          canManage ? (
                            <Button
                              size="small"
                              startIcon={<RestoreIcon />}
                              disabled={busy}
                              onClick={() => void toggleContainerRequirement(requirement)}
                            >
                              Restore
                            </Button>
                          ) : undefined
                        }
                      >
                        <ListItemText
                          primary={requirementLabel(requirement, models)}
                          secondary="Archived"
                        />
                      </ListItem>
                    ))}
                  </List>
                </section>
              ) : null}
            </Stack>
          )}
        </Stack>

        <Dialog
          open={Boolean(requirementEditor)}
          onClose={() => !busy && setRequirementEditor(undefined)}
          fullWidth
          maxWidth="sm"
        >
          <form onSubmit={(event) => void saveRequirement(event)}>
            <DialogTitle>
              {requirementEditor?.requirement ? "Edit" : "Add"}{" "}
              {requirementEditor?.scope === "template" ? "template " : ""}packing requirement
            </DialogTitle>
            <DialogContent>
              <Stack spacing={2} sx={{ pt: 1 }}>
                {error ? <Alert severity="error">{error}</Alert> : null}
                <TextField
                  select
                  required
                  label="Requirement kind"
                  value={type}
                  onChange={(event) => {
                    setType(event.target.value as PackingRequirementInput["type"]);
                    setAssetModelId("");
                    setSpecificAssetReference("");
                    setSelectedAsset(null);
                    setAssetQuery("");
                    setAssignToContainer(false);
                    setQuantity("1");
                  }}
                >
                  <MenuItem value="MODEL_QUANTITY">Interchangeable serialized</MenuItem>
                  <MenuItem value="CONSUMABLE_QUANTITY">Consumable minimum</MenuItem>
                  <MenuItem value="SPECIFIC_ASSET">Exact asset</MenuItem>
                </TextField>
                {type === "SPECIFIC_ASSET" ? (
                  <>
                    <Autocomplete
                      options={assetOptionsQuery === assetQuery ? assetOptions : []}
                      value={selectedAsset}
                      filterOptions={(options) => options}
                      loading={assetOptionsQuery !== assetQuery}
                      onInputChange={(_, value, reason) => {
                        if (reason === "input" || reason === "clear") setAssetQuery(value);
                      }}
                      onChange={(_, value) => {
                        ++selectionRequest.current;
                        setSelectedAsset(value);
                        setSpecificAssetReference(value?.publicCode ?? "");
                      }}
                      isOptionEqualToValue={(option, value) => option.id === value.id}
                      getOptionLabel={(option) => `${option.displayName} — ${option.publicCode}`}
                      renderOption={(props, option) => (
                        <li {...props} key={option.id}>
                          <Stack>
                            <Typography>
                              {option.displayName}{" "}
                              <Typography component="span" sx={{ fontFamily: "monospace" }}>
                                {option.publicCode}
                              </Typography>
                            </Typography>
                            <Typography variant="body2" color="text.secondary">
                              {option.assetModelName} ·{" "}
                              {option.parentContainerAssetId ? "In a container" : "Unplaced"}
                            </Typography>
                          </Stack>
                        </li>
                      )}
                      renderInput={(params) => (
                        <TextField
                          {...params}
                          required
                          label="Exact asset"
                          error={Boolean(assetSearchError)}
                          helperText={
                            assetSearchError ?? "Search by asset name, model, or printed code."
                          }
                        />
                      )}
                    />
                    {requirementEditor?.scope === "container" ? (
                      <FormControlLabel
                        control={
                          <Checkbox
                            checked={assignToContainer}
                            disabled={!selectedAsset}
                            onChange={(event) => setAssignToContainer(event.target.checked)}
                          />
                        }
                        label="Assign this asset to this container now"
                      />
                    ) : null}
                  </>
                ) : (
                  <TextField
                    select
                    required
                    label="Model"
                    value={assetModelId}
                    onChange={(event) => setAssetModelId(event.target.value)}
                  >
                    <MenuItem value="">Select model</MenuItem>
                    {visibleModels.map((model) => (
                      <MenuItem key={model.id} value={model.id}>
                        {model.name}
                      </MenuItem>
                    ))}
                  </TextField>
                )}
                <TextField
                  required
                  label="Required quantity"
                  type="number"
                  value={type === "SPECIFIC_ASSET" ? "1" : quantity}
                  disabled={type === "SPECIFIC_ASSET"}
                  onChange={(event) => setQuantity(event.target.value)}
                  slotProps={{
                    htmlInput: {
                      min: type === "CONSUMABLE_QUANTITY" ? 0.001 : 1,
                      step: type === "CONSUMABLE_QUANTITY" ? 0.001 : 1,
                    },
                  }}
                />
              </Stack>
            </DialogContent>
            <DialogActions>
              <Button onClick={() => setRequirementEditor(undefined)} disabled={busy}>
                Cancel
              </Button>
              <Button
                type="submit"
                disabled={busy || (type === "SPECIFIC_ASSET" && !selectedAsset)}
              >
                Save
              </Button>
            </DialogActions>
          </form>
        </Dialog>

        <Dialog
          open={Boolean(pendingArchive)}
          onClose={() => !busy && setPendingArchive(undefined)}
        >
          <DialogTitle>Confirm reservation impact</DialogTitle>
          <DialogContent>
            <Stack spacing={1}>
              <Typography>
                Removing this packing requirement changes future reservations. Review the affected
                events, then confirm the change.
              </Typography>
              {affectedBookingIds?.map((bookingId) => (
                <Button key={bookingId} component={RouterLink} to={`/events/${bookingId}`}>
                  View affected event
                </Button>
              ))}
            </Stack>
          </DialogContent>
          <DialogActions>
            <Button onClick={() => setPendingArchive(undefined)} disabled={busy}>
              Cancel
            </Button>
            <Button
              color="warning"
              variant="contained"
              disabled={busy || !pendingArchive}
              onClick={() =>
                void run(async () => {
                  if (!pendingArchive) return;
                  const failure = await archivePackingRequirement(
                    pendingArchive.id,
                    pendingArchive.version,
                    true,
                  );
                  if (failure) {
                    setError(errorMessage(failure));
                    return;
                  }
                  setPendingArchive(undefined);
                  setAffectedBookingIds(undefined);
                  await reload();
                })
              }
            >
              Confirm removal
            </Button>
          </DialogActions>
        </Dialog>

        <Dialog
          open={templateLibraryOpen}
          onClose={() => !busy && setTemplateLibraryOpen(false)}
          fullWidth
          maxWidth="md"
        >
          <DialogTitle>Manage packing templates</DialogTitle>
          <DialogContent>
            <Typography color="text.secondary">
              Applying a template copies its requirements. Later template edits never change
              existing containers.
            </Typography>
            <Button sx={{ mt: 1 }} onClick={() => openTemplateEditor()}>
              New template
            </Button>
            <Stack spacing={1.5} sx={{ mt: 1.5 }}>
              {templates.map((template) => (
                <Paper key={template.id} variant="outlined" sx={{ p: 1.5 }}>
                  <Stack spacing={1}>
                    <Stack
                      direction={{ xs: "column", sm: "row" }}
                      sx={{ justifyContent: "space-between" }}
                    >
                      <div>
                        <Typography sx={{ fontWeight: 700 }}>
                          {template.name}
                          {template.archived ? " (archived)" : ""}
                        </Typography>
                        {template.description ? (
                          <Typography color="text.secondary">{template.description}</Typography>
                        ) : null}
                      </div>
                      <Stack direction="row" spacing={0.5}>
                        <Button
                          size="small"
                          disabled={busy}
                          onClick={() => openTemplateEditor(template)}
                        >
                          Edit
                        </Button>
                        <Button
                          size="small"
                          disabled={busy}
                          startIcon={template.archived ? <RestoreIcon /> : <ArchiveIcon />}
                          onClick={() => void toggleTemplate(template)}
                        >
                          {template.archived ? "Restore" : "Archive"}
                        </Button>
                      </Stack>
                    </Stack>
                    <Divider />
                    <List dense disablePadding>
                      {(template.requirements ?? []).map((requirement) => (
                        <ListItem
                          key={requirement.id}
                          secondaryAction={
                            <Stack direction="row" spacing={0.5}>
                              <Button
                                size="small"
                                disabled={busy}
                                onClick={() =>
                                  openRequirementEditor({
                                    scope: "template",
                                    templateId: template.id,
                                    requirement,
                                  })
                                }
                              >
                                Edit
                              </Button>
                              <Button
                                size="small"
                                disabled={busy}
                                onClick={() => void toggleTemplateRequirement(requirement)}
                              >
                                {requirement.archived ? "Restore" : "Archive"}
                              </Button>
                            </Stack>
                          }
                        >
                          <ListItemText
                            primary={requirementLabel(requirement, models)}
                            secondary={`${requirement.archived ? "Archived · " : ""}Required: ${requirement.requiredQuantity}`}
                          />
                        </ListItem>
                      ))}
                    </List>
                    <Button
                      size="small"
                      sx={{ alignSelf: "flex-start" }}
                      disabled={busy || template.archived}
                      onClick={() =>
                        openRequirementEditor({ scope: "template", templateId: template.id })
                      }
                    >
                      Add template requirement
                    </Button>
                  </Stack>
                </Paper>
              ))}
            </Stack>
          </DialogContent>
          <DialogActions>
            <Button onClick={() => setTemplateLibraryOpen(false)} disabled={busy}>
              Close
            </Button>
          </DialogActions>
        </Dialog>

        <Dialog
          open={templateEditor !== undefined}
          onClose={() => !busy && setTemplateEditor(undefined)}
          fullWidth
          maxWidth="sm"
        >
          <form onSubmit={(event) => void saveTemplate(event)}>
            <DialogTitle>
              {templateEditor?.id ? "Edit packing template" : "Create packing template"}
            </DialogTitle>
            <DialogContent>
              <Stack spacing={2} sx={{ pt: 1 }}>
                <TextField
                  required
                  label="Template name"
                  value={templateName}
                  onChange={(event) => setTemplateName(event.target.value)}
                />
                <TextField
                  multiline
                  minRows={2}
                  label="Description"
                  value={templateDescription}
                  onChange={(event) => setTemplateDescription(event.target.value)}
                />
              </Stack>
            </DialogContent>
            <DialogActions>
              <Button onClick={() => setTemplateEditor(undefined)} disabled={busy}>
                Cancel
              </Button>
              <Button type="submit" disabled={busy || !templateName.trim()}>
                {templateEditor?.id ? "Save template" : "Create template"}
              </Button>
            </DialogActions>
          </form>
        </Dialog>
      </AccordionDetails>
    </Accordion>
  );
}
