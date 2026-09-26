import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemText from "@mui/material/ListItemText";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import {
  errorMessage,
  getAssetPlacement,
  listAssetModels,
  listAssets,
  listContainerContents,
  listContainerStock,
  listLocations,
  moveAsset,
  type AssetPlacementRecord,
  type AssetRecord,
  type LocationRecord,
  type StockBalanceRecord,
} from "./inventoryApi";

export function AssetPlacementPanel({
  assetId,
  canManage,
}: {
  assetId: string;
  canManage: boolean;
}) {
  const [placement, setPlacement] = useState<AssetPlacementRecord>();
  const [locations, setLocations] = useState<LocationRecord[]>([]);
  const [containers, setContainers] = useState<AssetRecord[]>([]);
  const [contents, setContents] = useState<AssetPlacementRecord[]>([]);
  const [stock, setStock] = useState<StockBalanceRecord[]>([]);
  const [locationId, setLocationId] = useState("");
  const [parentContainerAssetId, setParentContainerAssetId] = useState("");
  const [error, setError] = useState<string>();
  async function load() {
    const [placementResult, locationsResult, modelsResult, contentsResult, stockResult] =
      await Promise.all([
        getAssetPlacement(assetId),
        listLocations(),
        listAssetModels(),
        listContainerContents(assetId),
        listContainerStock(assetId),
      ]);
    if (placementResult.kind === "error") {
      setError(errorMessage(placementResult.error));
      return;
    }
    if (locationsResult.kind === "error") {
      setError(errorMessage(locationsResult.error));
      return;
    }
    if (modelsResult.kind === "error") {
      setError(errorMessage(modelsResult.error));
      return;
    }
    const resultLists = await Promise.all(
      modelsResult.data
        .filter((model) => model.canContainAssets && !model.archived)
        .map((model) => listAssets(model.id, false)),
    );
    const failed = resultLists.find((result) => result.kind === "error");
    if (failed?.kind === "error") {
      setError(errorMessage(failed.error));
      return;
    }
    setPlacement(placementResult.data);
    setLocations(locationsResult.data);
    setContainers(
      resultLists
        .flatMap((result) => (result.kind === "ok" ? result.data : []))
        .filter((asset) => asset.id !== assetId),
    );
    if (contentsResult.kind === "ok") setContents(contentsResult.data);
    if (stockResult.kind === "ok") setStock(stockResult.data);
    setLocationId(placementResult.data.directLocationId ?? "");
    setParentContainerAssetId(placementResult.data.parentContainerAssetId ?? "");
    setError(undefined);
  }
  useEffect(() => {
    void load();
  }, [assetId]);
  async function save() {
    if (!placement) return;
    const result = await moveAsset(assetId, {
      locationId: parentContainerAssetId ? undefined : locationId || undefined,
      parentContainerAssetId: parentContainerAssetId || undefined,
      expectedVersion: placement.version,
    });
    if (result.kind === "error") {
      setError(errorMessage(result.error));
      return;
    }
    await load();
  }
  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Stack spacing={1.5}>
        <Typography variant="h3">Placement</Typography>
        {error ? <Alert severity="error">{error}</Alert> : null}
        <Typography color="text.secondary">
          {placement?.effectivePathText ?? "Loading physical path."}
        </Typography>
        {canManage ? (
          <Stack spacing={1}>
            <TextField
              select
              label="Direct location"
              value={locationId}
              disabled={Boolean(parentContainerAssetId)}
              onChange={(event) => setLocationId(event.target.value)}
            >
              <MenuItem value="">Unplaced</MenuItem>
              {locations
                .filter((location) => !location.archived)
                .map((location) => (
                  <MenuItem key={location.id} value={location.id}>
                    {location.effectivePath}
                  </MenuItem>
                ))}
            </TextField>
            <TextField
              select
              label="Parent container"
              value={parentContainerAssetId}
              disabled={Boolean(locationId)}
              onChange={(event) => setParentContainerAssetId(event.target.value)}
            >
              <MenuItem value="">No parent container</MenuItem>
              {containers.map((container) => (
                <MenuItem key={container.id} value={container.id}>
                  {container.displayName}
                </MenuItem>
              ))}
            </TextField>
            <Button variant="outlined" onClick={() => void save()} disabled={!placement}>
              Save placement
            </Button>
          </Stack>
        ) : null}
        {contents.length > 0 ? (
          <>
            <Typography variant="h4">Direct contents</Typography>
            <List dense>
              {contents.map((content) => (
                <ListItem key={content.assetId}>
                  <ListItemText
                    primary={content.effectivePath.at(-1)}
                    secondary={content.effectivePathText}
                  />
                </ListItem>
              ))}
            </List>
          </>
        ) : null}
        {stock.length > 0 ? (
          <>
            <Typography variant="h4">Consumable stock</Typography>
            <List dense>
              {stock.map((balance) => (
                <ListItem key={balance.id}>
                  <ListItemText
                    primary={balance.assetModelName}
                    secondary={`${balance.quantity} ${balance.stockUnitLabel}`}
                  />
                </ListItem>
              ))}
            </List>
          </>
        ) : null}
      </Stack>
    </Paper>
  );
}
