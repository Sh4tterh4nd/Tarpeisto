import { useEffect, useRef, useState } from "react";
import Alert from "@mui/material/Alert";
import Autocomplete from "@mui/material/Autocomplete";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Divider from "@mui/material/Divider";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import {
  applyAssetSeal,
  breakAssetSeal,
  changeAssetCondition,
  changeAssetLifecycle,
  errorMessage,
  getAssetPlacement,
  listCustomFields,
  listCustomFieldOptions,
  listLocations,
  moveAsset,
  renameAsset,
  searchAssets,
  setAssetPurchaseDate,
  setAssetSealable,
  setAssetValues,
  type AssetRecord,
  type AssetPlacementRecord,
  type AssetValueInput,
  type CustomFieldRecord,
  type CustomFieldOptionRecord,
  type LocationRecord,
  type AssetSearchRecord,
} from "./inventoryApi";

import { AssetPackingDetailsEditor } from "./AssetPackingDetailsEditor";

/** Each section saves independently; failed sections keep their local draft. */
export function AssetDetailsEditor({
  asset,
  placement,
  containerCapable,
  onSaved,
  onBusyChange,
}: {
  asset: AssetRecord;
  placement: AssetPlacementRecord;
  containerCapable: boolean;
  onSaved: () => Promise<void>;
  onBusyChange?: (busy: boolean) => void;
}) {
  const [name, setName] = useState(asset.individualName ?? "");
  const [date, setDate] = useState(asset.purchaseDate ?? "");
  const [condition, setCondition] = useState(asset.condition);
  const [lifecycle, setLifecycle] = useState(asset.lifecycleState);
  const [conditionReason, setConditionReason] = useState("");
  const [lifecycleReason, setLifecycleReason] = useState("");
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      asset.values.map((value) => [
        value.fieldId,
        value.stringValue || value.dateValue || value.optionId || "",
      ]),
    ),
  );
  const [fields, setFields] = useState<CustomFieldRecord[]>();
  const [options, setOptions] = useState<Record<string, CustomFieldOptionRecord[]>>({});
  const [locations, setLocations] = useState<LocationRecord[]>([]);
  const [locationId, setLocationId] = useState(placement.directLocationId ?? "");
  const [parentId, setParentId] = useState(placement.parentContainerAssetId ?? "");
  const [containerQuery, setContainerQuery] = useState("");
  const [containers, setContainers] = useState<AssetSearchRecord[]>([]);
  const [sealable, setSealable] = useState(asset.sealable);
  const [seal, setSeal] = useState<"" | "OPEN" | "APPLIED">("");
  const [expectedVersion, setExpectedVersion] = useState(placement.version);
  const [error, setError] = useState<string>();
  const [saved, setSaved] = useState<string>();
  const [busy, setBusy] = useState<string>();
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  useEffect(() => {
    let active = true;
    void (async () => {
      const [fieldResult, locationResult] = await Promise.all([
        listCustomFields(asset.assetModelId),
        listLocations(true),
      ]);
      if (!active) return;
      if (fieldResult.kind === "error") {
        setError(errorMessage(fieldResult.error));
        return;
      }
      if (locationResult.kind === "error") {
        setError(errorMessage(locationResult.error));
        return;
      }
      setFields(fieldResult.data.filter((field) => !field.archived));
      setLocations(
        locationResult.data.filter(
          (location) => !location.archived || location.id === placement.directLocationId,
        ),
      );
      const results = await Promise.all(
        fieldResult.data
          .filter((field) => !field.archived && field.dataType === "DROPDOWN")
          .map(
            async (field) =>
              [field.id, await listCustomFieldOptions(asset.assetModelId, field.id, true)] as const,
          ),
      );
      if (!active) return;
      const loaded: Record<string, CustomFieldOptionRecord[]> = {};
      for (const [fieldId, result] of results) {
        if (result.kind === "error") {
          setError(errorMessage(result.error));
          return;
        }
        loaded[fieldId] = result.data.filter(
          (option) =>
            !option.archived ||
            option.id === asset.values.find((value) => value.fieldId === fieldId)?.optionId,
        );
      }
      setOptions(loaded);
    })();
    return () => {
      active = false;
    };
  }, [asset.assetModelId, asset.values, placement.directLocationId]);
  useEffect(() => {
    let active = true;
    const timer = setTimeout(() => {
      void searchAssets({
        query: containerQuery || undefined,
        containerOnly: true,
        limit: 25,
      }).then((result) => {
        if (!active) return;
        if (result.kind === "error") {
          setError(errorMessage(result.error));
          return;
        }
        setContainers(result.data.items.filter((row) => row.id !== asset.id));
      });
    }, 200);
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [containerQuery, asset.id]);
  async function save(section: string, operation: () => Promise<string | undefined>) {
    if (busy) return;
    setBusy(section);
    onBusyChange?.(true);
    setError(undefined);
    setSaved(undefined);
    const failure = await operation();
    if (!alive.current) return;
    setBusy(undefined);
    onBusyChange?.(false);
    if (failure) {
      setError(failure);
      return;
    }
    setSaved(`${section} saved.`);
    await onSaved();
  }
  const readFailure = (
    result: { kind: "ok" } | { kind: "error"; error: Parameters<typeof errorMessage>[0] },
  ) => (result.kind === "error" ? errorMessage(result.error) : undefined);
  const saveButton = (
    section: string,
    operation: () => Promise<string | undefined>,
    disabled = false,
  ) => (
    <Button
      variant="outlined"
      disabled={Boolean(busy) || disabled}
      onClick={() => void save(section, operation)}
      sx={{ alignSelf: "flex-start", minHeight: 44 }}
    >
      Save {section.toLowerCase()}
    </Button>
  );
  return (
    <Stack spacing={2} sx={{ p: 2 }}>
      <Typography variant="h3">Edit asset</Typography>
      <Typography variant="body2" color="text.secondary">
        Save each section separately. Done or Cancel discards unsaved changes.
      </Typography>
      {error ? <Alert severity="error">{error}</Alert> : null}
      {saved ? <Alert severity="success">{saved}</Alert> : null}
      <TextField
        label="Individual name (optional)"
        value={name}
        onChange={(event) => setName(event.target.value)}
      />
      {saveButton("Name", async () =>
        readFailure(await renameAsset(asset.id, name.trim() || undefined)),
      )}
      <Divider />
      {containerCapable ? (
        <>
          <AssetPackingDetailsEditor
            asset={asset}
            onSaved={onSaved}
            disabled={Boolean(busy)}
            onBusyChange={(packingBusy) => {
              setBusy(packingBusy ? "Packing details" : undefined);
              onBusyChange?.(packingBusy);
            }}
          />
          <Divider />
        </>
      ) : null}
      <TextField
        label="Purchase date (optional)"
        type="date"
        slotProps={{ inputLabel: { shrink: true } }}
        value={date}
        onChange={(event) => setDate(event.target.value)}
      />
      {saveButton("Purchase date", async () =>
        readFailure(await setAssetPurchaseDate(asset.id, date || undefined)),
      )}
      <Divider />
      <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" }, gap: 2 }}>
        <Stack spacing={1}>
          <TextField
            label="Condition"
            select
            value={condition}
            onChange={(event) => setCondition(event.target.value as typeof condition)}
          >
            <MenuItem value="GOOD">Good</MenuItem>
            <MenuItem value="DAMAGED">Damaged</MenuItem>
          </TextField>
          <TextField
            label="Condition reason (optional)"
            value={conditionReason}
            onChange={(event) => setConditionReason(event.target.value)}
          />
          {saveButton("Condition", async () =>
            readFailure(
              await changeAssetCondition(asset.id, condition, conditionReason.trim() || undefined),
            ),
          )}
        </Stack>
        <Stack spacing={1}>
          <TextField
            label="Lifecycle"
            select
            value={lifecycle}
            onChange={(event) => setLifecycle(event.target.value as typeof lifecycle)}
          >
            {["ACTIVE", "LOST", "DESTROYED", "RETIRED"].map((value) => (
              <MenuItem key={value} value={value}>
                {value.charAt(0) + value.slice(1).toLowerCase()}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label="Lifecycle reason (optional)"
            value={lifecycleReason}
            onChange={(event) => setLifecycleReason(event.target.value)}
          />
          {saveButton("Lifecycle", async () =>
            readFailure(
              await changeAssetLifecycle(asset.id, lifecycle, lifecycleReason.trim() || undefined),
            ),
          )}
        </Stack>
      </Box>
      <Divider />
      <Typography variant="h4">Placement</Typography>
      <Button
        disabled={Boolean(busy)}
        sx={{ alignSelf: "flex-start", minHeight: 44 }}
        onClick={() => {
          void (async () => {
            const fresh = await getAssetPlacement(asset.id);
            if (!alive.current) return;
            if (fresh.kind === "error") {
              setError(errorMessage(fresh.error));
              return;
            }
            setLocationId(fresh.data.directLocationId ?? "");
            setParentId(fresh.data.parentContainerAssetId ?? "");
            setExpectedVersion(fresh.data.version);
            setSeal("");
            setError(undefined);
            await onSaved();
          })();
        }}
      >
        Reload placement and seal
      </Button>
      <Typography variant="body2" color="text.secondary">
        Reload to review current placement and seal after a conflict. This discards those two
        unsaved choices.
      </Typography>
      <TextField
        label="Direct location"
        select
        value={locationId}
        onChange={(event) => {
          setLocationId(event.target.value);
          if (event.target.value) setParentId("");
        }}
      >
        <MenuItem value="">No direct location</MenuItem>
        {locations.map((location) => (
          <MenuItem key={location.id} value={location.id}>
            {location.effectivePath}
            {location.archived ? " (archived)" : ""}
          </MenuItem>
        ))}
      </TextField>
      <Autocomplete
        options={containers}
        filterOptions={(items) => items}
        value={containers.find((row) => row.id === parentId) ?? null}
        onInputChange={(_event, text, reason) => {
          if (reason === "input") setContainerQuery(text);
        }}
        onChange={(_event, row) => {
          setParentId(row?.id ?? "");
          if (row) setLocationId("");
        }}
        getOptionLabel={(row) => `${row.displayName} (${row.publicCode})`}
        renderInput={(params) => (
          <TextField
            {...params}
            label="Parent container"
            placeholder={
              parentId ? placement.effectivePath.at(-2) : "Search container name or code"
            }
          />
        )}
      />
      {parentId && !containers.some((row) => row.id === parentId) ? (
        <Typography variant="body2">
          Current parent: {placement.effectivePath.at(-2)}{" "}
          <Button onClick={() => setParentId("")}>Remove parent</Button>
        </Typography>
      ) : null}
      {saveButton("Placement", async () => {
        return readFailure(
          await moveAsset(asset.id, {
            locationId: locationId || undefined,
            parentContainerAssetId: parentId || undefined,
            expectedVersion,
          }),
        );
      })}
      {containerCapable ? (
        <>
          <Divider />
          <Typography variant="h4">Physical seal</Typography>
          <TextField
            label="Sealability"
            select
            value={sealable ? "SEALABLE" : "NOT_SEALABLE"}
            onChange={(event) => setSealable(event.target.value === "SEALABLE")}
          >
            <MenuItem value="NOT_SEALABLE">Not sealable</MenuItem>
            <MenuItem value="SEALABLE">Sealable</MenuItem>
          </TextField>
          {saveButton("Sealability", async () => {
            const failure = await setAssetSealable(asset.id, sealable);
            return failure ? errorMessage(failure) : undefined;
          })}
          {asset.sealable ? (
            <>
              <Typography variant="body2">
                Current seal: {asset.sealState.replaceAll("_", " ").toLowerCase()}
              </Typography>
              <TextField
                label="Physical seal state"
                select
                value={seal}
                onChange={(event) => setSeal(event.target.value as typeof seal)}
              >
                <MenuItem value="">Choose physical action</MenuItem>
                <MenuItem value="OPEN">Open</MenuItem>
                <MenuItem value="APPLIED">Applied</MenuItem>
              </TextField>
              <Typography variant="body2" color="text.secondary">
                Applied is a physical seal. Verified status comes from an audit; applying or opening
                a seal requires fresh verification.
              </Typography>
              {saveButton(
                "Physical seal",
                async () => {
                  const failure =
                    seal === "APPLIED"
                      ? await applyAssetSeal(asset.id, { expectedVersion })
                      : await breakAssetSeal(asset.id);
                  return failure ? errorMessage(failure) : undefined;
                },
                !seal,
              )}
            </>
          ) : null}
        </>
      ) : null}
      {fields?.length ? (
        <>
          <Divider />
          <Typography variant="h4">Model-defined values</Typography>
          {fields.map((field) => (
            <TextField
              key={field.id}
              label={field.name}
              select={field.dataType === "DROPDOWN"}
              type={field.dataType === "DATE" ? "date" : "text"}
              slotProps={field.dataType === "DATE" ? { inputLabel: { shrink: true } } : undefined}
              value={values[field.id] ?? ""}
              onChange={(event) =>
                setValues((current) => ({ ...current, [field.id]: event.target.value }))
              }
              required
            >
              {field.dataType === "DROPDOWN"
                ? (options[field.id] ?? []).map((option) => (
                    <MenuItem key={option.id} value={option.id}>
                      {option.value}
                      {option.archived ? " (archived)" : ""}
                    </MenuItem>
                  ))
                : null}
            </TextField>
          ))}
          {saveButton(
            "Values",
            async () => {
              const inputs: AssetValueInput[] = fields.map((field) => ({
                fieldId: field.id,
                ...(field.dataType === "DATE"
                  ? { dateValue: values[field.id] || undefined }
                  : field.dataType === "DROPDOWN"
                    ? { optionId: values[field.id] || undefined }
                    : { stringValue: values[field.id] || undefined }),
              }));
              return readFailure(await setAssetValues(asset.id, inputs));
            },
            !fields.every((field) => values[field.id]?.trim()),
          )}
        </>
      ) : null}
    </Stack>
  );
}
