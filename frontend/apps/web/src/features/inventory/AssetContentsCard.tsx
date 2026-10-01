import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import Collapse from "@mui/material/Collapse";
import Divider from "@mui/material/Divider";
import Link from "@mui/material/Link";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import WarningAmberIcon from "@mui/icons-material/WarningAmber";
import { Link as RouterLink } from "react-router-dom";
import {
  errorMessage,
  getPackingContents,
  type PackingContents,
  type PackingContentIdentity,
} from "./inventoryApi";

function AssetLink({ asset }: { asset: PackingContentIdentity }) {
  return (
    <Link
      color="inherit"
      component={RouterLink}
      to={`/inventory/assets/${asset.assetId}`}
      sx={{ overflowWrap: "anywhere" }}
    >
      {asset.displayName}{" "}
      <Box component="span" sx={{ fontFamily: "monospace", whiteSpace: "nowrap" }}>
        {asset.publicCode}
      </Box>
    </Link>
  );
}

export function AssetContentsCard({
  assetId,
  revision,
  downloadAction,
  downloadError,
  onDownloadRetry,
}: {
  assetId: string;
  revision: number;
  downloadAction?: ReactNode;
  downloadError?: string;
  onDownloadRetry?: () => void;
}) {
  const [data, setData] = useState<PackingContents>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [stale, setStale] = useState(false);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const request = useRef<symbol | undefined>(undefined);
  const load = useCallback(
    async (append?: PackingContents) => {
      const epoch = Symbol();
      request.current = epoch;
      setBusy(true);
      setError(undefined);
      if (!append) {
        setData(undefined);
        setExpanded({});
        setStale(false);
      }
      const result = await getPackingContents(assetId, append?.nextCursor || undefined);
      if (epoch !== request.current) return;
      setBusy(false);
      if (result.kind === "error") {
        setError(errorMessage(result.error));
        if (result.error.status === 409) {
          setData(undefined);
          setStale(true);
        }
        return;
      }
      setData(
        append
          ? {
              ...result.data,
              assets: [...append.assets, ...result.data.assets],
              requirements: [...append.requirements, ...result.data.requirements],
              consumables: [...append.consumables, ...result.data.consumables],
            }
          : result.data,
      );
    },
    [assetId],
  );
  useEffect(() => {
    const timer = setTimeout(() => {
      void load();
    }, 0);
    return () => {
      clearTimeout(timer);
      request.current = undefined;
    };
  }, [load, revision]);
  const problems = data?.assets.filter((row) => row.status !== "MATCHED") ?? [];
  const matched = data?.assets.filter((row) => row.status === "MATCHED") ?? [];
  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, sm: 3 } }}>
      <Stack spacing={2}>
        <Stack
          direction={{ xs: "column", sm: "row" }}
          spacing={1}
          sx={{ alignItems: { xs: "flex-start", sm: "center" }, justifyContent: "space-between" }}
        >
          <Typography variant="h3">Direct contents</Typography>
          {downloadAction}
          <Button onClick={() => void load()} disabled={busy} sx={{ minHeight: 44 }}>
            Refresh contents
          </Button>
        </Stack>
        {downloadError ? (
          <Alert
            severity="error"
            action={
              <Button color="inherit" onClick={onDownloadRetry}>
                Retry download
              </Button>
            }
          >
            Could not download the packing sheet: {downloadError}
          </Alert>
        ) : null}
        {error ? (
          <Alert
            severity="error"
            action={
              <Button color="inherit" onClick={() => void load()}>
                {stale ? "Refresh" : "Retry"}
              </Button>
            }
          >
            {error}
          </Alert>
        ) : null}
        {busy ? <CircularProgress size={22} aria-label="Loading contents" /> : null}
        {data ? (
          <>
            <Typography color="text.secondary">
              {data.totalAssetCount} serialized assets / {data.totalConsumableCount} stock balances
              / {data.totalRequirementCount} requirements
            </Typography>
            {data.complete ? (
              <Typography color="success.main">Packing is complete.</Typography>
            ) : (
              <Typography color="text.secondary">Packing needs attention.</Typography>
            )}
            {problems.map((row) => (
              <Stack
                key={row.asset.assetId}
                direction={{ xs: "column", sm: "row" }}
                spacing={1}
                sx={{
                  p: 1.5,
                  borderLeft: 3,
                  borderColor: "warning.main",
                  color: "warning.main",
                  bgcolor: "action.hover",
                }}
              >
                <Typography color="warning.main" sx={{ fontWeight: 700 }}>
                  {row.status === "INACTIVE"
                    ? "Inactive"
                    : row.status === "MISPLACED"
                      ? "Misplaced"
                      : "Extra"}
                </Typography>
                <AssetLink asset={row.asset} />
              </Stack>
            ))}
            {data.requirements
              .filter((row) => row.missingQuantity > 0 && row.type !== "MODEL_QUANTITY")
              .map((row) => (
                <Stack
                  key={row.id}
                  direction="row"
                  spacing={1}
                  sx={{ alignItems: "flex-start", color: "error.main" }}
                >
                  <WarningAmberIcon fontSize="small" />
                  <Typography>
                    Missing {row.missingQuantity}
                    {row.unitLabel ? ` ${row.unitLabel}` : ""}:{" "}
                    {row.exactAsset ? <AssetLink asset={row.exactAsset} /> : row.assetModelName}
                  </Typography>
                </Stack>
              ))}
            {data.requirements
              .filter((row) => row.type === "SPECIFIC_ASSET" && row.satisfied && row.exactAsset)
              .map((row) => (
                <Box key={row.id} sx={{ py: 1 }}>
                  <AssetLink asset={row.exactAsset!} />
                </Box>
              ))}
            {data.requirements
              .filter((row) => row.type === "MODEL_QUANTITY")
              .map((row) => (
                <Box key={row.id}>
                  <Button
                    color={row.missingQuantity > 0 ? "error" : "inherit"}
                    startIcon={row.missingQuantity > 0 ? <WarningAmberIcon /> : undefined}
                    endIcon={
                      <ExpandMoreIcon
                        sx={{ transform: expanded[row.id] ? "rotate(180deg)" : undefined }}
                      />
                    }
                    aria-expanded={Boolean(expanded[row.id])}
                    onClick={() =>
                      setExpanded((current) => ({ ...current, [row.id]: !current[row.id] }))
                    }
                    sx={{
                      minHeight: 44,
                      justifyContent: "flex-start",
                      textAlign: "left",
                      width: "100%",
                    }}
                  >
                    {row.presentQuantity}
                    {"\u00d7"} {row.assetModelName}
                    {row.missingQuantity > 0 ? ` (${row.missingQuantity} missing)` : ""}
                  </Button>
                  <Collapse in={expanded[row.id]}>
                    <Stack spacing={1} sx={{ pl: 2, pb: 1 }}>
                      {matched
                        .filter((entry) => entry.requirementId === row.id)
                        .map((entry) => (
                          <AssetLink key={entry.asset.assetId} asset={entry.asset} />
                        ))}
                      {matched.filter((entry) => entry.requirementId === row.id).length <
                      row.presentQuantity ? (
                        <Typography variant="body2" color="text.secondary">
                          Load more contents to see all matched codes.
                        </Typography>
                      ) : null}
                    </Stack>
                  </Collapse>
                </Box>
              ))}
            {data.consumables.length ? (
              <>
                <Divider />
                <Typography variant="h4">Consumable stock</Typography>
                {data.consumables.map((row) => (
                  <Typography key={row.assetModelId}>
                    {row.quantity} {row.unitLabel} / {row.assetModelName}
                    {row.archived ? " (archived balance)" : ""}
                  </Typography>
                ))}
              </>
            ) : null}
            {!data.totalAssetCount && !data.totalConsumableCount ? (
              <Typography color="text.secondary">No direct contents.</Typography>
            ) : null}
            {data.nextCursor ? (
              <Button
                variant="outlined"
                onClick={() => void load(data)}
                disabled={busy}
                sx={{ alignSelf: "flex-start", minHeight: 44 }}
              >
                Show more contents and requirements
              </Button>
            ) : null}
          </>
        ) : null}
      </Stack>
    </Paper>
  );
}
