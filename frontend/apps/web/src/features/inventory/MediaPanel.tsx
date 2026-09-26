import { useCallback, useEffect, useRef, useState } from "react";
import DeleteIcon from "@mui/icons-material/Delete";
import PhotoCameraOutlinedIcon from "@mui/icons-material/PhotoCameraOutlined";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import IconButton from "@mui/material/IconButton";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import {
  deleteMedia,
  getMedia,
  listMedia,
  updateLayoutMedia,
  uploadMedia,
  type MediaRecord,
} from "./mediaApi";

function Photo({ media, alt }: { media: MediaRecord; alt: string }) {
  return (
    <Box
      component="img"
      src={media.thumbnailUrl}
      alt={alt}
      sx={{ width: "100%", height: 160, objectFit: "cover", display: "block" }}
    />
  );
}

export function MediaPanel({
  assetModelId,
  assetId,
  fallbackAssetModelId,
  containerCapable,
  canManage,
}: {
  assetModelId?: string;
  assetId?: string;
  fallbackAssetModelId?: string;
  containerCapable?: boolean;
  canManage: boolean;
}) {
  const referencePath = assetId
    ? `/api/v1/assets/${assetId}/media/reference`
    : `/api/v1/asset-models/${assetModelId}/media/reference`;
  const layoutPath = `/api/v1/assets/${assetId}/media/layout`;
  const [reference, setReference] = useState<MediaRecord>();
  const [referenceInherited, setReferenceInherited] = useState(false);
  const [layouts, setLayouts] = useState<MediaRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const referenceInput = useRef<HTMLInputElement>(null);
  const layoutInput = useRef<HTMLInputElement>(null);
  const [layoutCaption, setLayoutCaption] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [ownReference, nextLayouts] = await Promise.all([
        getMedia(referencePath),
        assetId && containerCapable ? listMedia(layoutPath) : Promise.resolve([]),
      ]);
      const nextReference =
        ownReference ??
        (fallbackAssetModelId
          ? await getMedia(`/api/v1/asset-models/${fallbackAssetModelId}/media/reference`)
          : undefined);
      setReference(nextReference);
      setReferenceInherited(!ownReference && Boolean(nextReference));
      setLayouts(nextLayouts);
      setError(undefined);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not load photos.");
    } finally {
      setLoading(false);
    }
  }, [assetId, containerCapable, fallbackAssetModelId, layoutPath, referencePath]);

  useEffect(() => {
    queueMicrotask(() => void load());
  }, [load]);

  async function upload(file: File | undefined, layout: boolean) {
    if (!file) return;
    try {
      await uploadMedia(
        layout ? layoutPath : referencePath,
        file,
        layout ? layoutCaption : undefined,
      );
      if (layout) setLayoutCaption("");
      await load();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not upload the photo.");
    }
  }

  async function remove(media: MediaRecord) {
    try {
      await deleteMedia(media.id);
      await load();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not remove the photo.");
    }
  }

  async function move(media: MediaRecord, direction: -1 | 1) {
    try {
      setLayouts(
        await updateLayoutMedia(
          media.id,
          media.caption ?? "",
          media.displayOrder + direction,
          media.primaryImage ?? false,
          media.version,
        ),
      );
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not reorder the layout photos.");
    }
  }

  async function editLayout(media: MediaRecord) {
    const caption = window.prompt("Caption", media.caption ?? "");
    if (caption === null) return;
    try {
      setLayouts(
        await updateLayoutMedia(
          media.id,
          caption,
          media.displayOrder,
          media.primaryImage ?? false,
          media.version,
        ),
      );
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not update the layout photo.");
    }
  }

  async function setPrimary(media: MediaRecord) {
    try {
      setLayouts(
        await updateLayoutMedia(
          media.id,
          media.caption ?? "",
          media.displayOrder,
          true,
          media.version,
        ),
      );
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not set the primary layout photo.");
    }
  }

  return (
    <Paper variant="outlined" sx={{ overflow: "hidden" }}>
      <Stack spacing={2} sx={{ p: 2 }}>
        <Stack
          direction="row"
          sx={{ alignItems: "center", justifyContent: "space-between", gap: 2 }}
        >
          <Box>
            <Typography variant="h3">Reference photo</Typography>
            <Typography variant="body2" color="text.secondary">
              A clear image for identifying this {assetId ? "unit" : "model"}.
            </Typography>
          </Box>
          {canManage ? (
            <Button
              startIcon={<PhotoCameraOutlinedIcon />}
              onClick={() => referenceInput.current?.click()}
            >
              {reference ? "Replace" : "Add photo"}
            </Button>
          ) : null}
        </Stack>
        <input
          ref={referenceInput}
          hidden
          type="file"
          accept="image/jpeg,image/png"
          onChange={(event) => void upload(event.target.files?.[0], false)}
        />
        {error ? (
          <Alert severity="error" onClose={() => setError(undefined)}>
            {error}
          </Alert>
        ) : null}
        {loading ? (
          <CircularProgress size={24} />
        ) : reference ? (
          <Stack direction={{ xs: "column", sm: "row" }} spacing={2} sx={{ maxWidth: 560 }}>
            <Box sx={{ width: { xs: "100%", sm: 240 }, overflow: "hidden", borderRadius: 1 }}>
              <Photo media={reference} alt="Reference" />
            </Box>
            {canManage && !referenceInherited ? (
              <Button color="error" onClick={() => void remove(reference)}>
                Remove photo
              </Button>
            ) : referenceInherited ? (
              <Typography color="text.secondary">Using the model reference photo.</Typography>
            ) : null}
          </Stack>
        ) : (
          <Typography color="text.secondary">No reference photo yet.</Typography>
        )}

        {assetId && containerCapable ? (
          <>
            <Box sx={{ borderTop: 1, borderColor: "divider", pt: 2 }}>
              <Typography variant="h3">Container layout</Typography>
              <Typography variant="body2" color="text.secondary">
                Keep photos in packing order, for example Bottom layer then Top tray.
              </Typography>
            </Box>
            {canManage ? (
              <Stack direction={{ xs: "column", sm: "row" }} spacing={1}>
                <TextField
                  label="Caption"
                  value={layoutCaption}
                  onChange={(event) => setLayoutCaption(event.target.value)}
                />
                <Button variant="outlined" onClick={() => layoutInput.current?.click()}>
                  Add layout photo
                </Button>
              </Stack>
            ) : null}
            <input
              ref={layoutInput}
              hidden
              type="file"
              accept="image/jpeg,image/png"
              onChange={(event) => void upload(event.target.files?.[0], true)}
            />
            {layouts.length === 0 ? (
              <Typography color="text.secondary">No layout photos yet.</Typography>
            ) : (
              <Stack direction="row" spacing={2} sx={{ overflowX: "auto", pb: 1 }}>
                {layouts.map((media, index) => (
                  <Paper
                    key={media.id}
                    variant="outlined"
                    sx={{ minWidth: 190, maxWidth: 220, overflow: "hidden" }}
                  >
                    <Photo media={media} alt={media.caption || `Layout photo ${index + 1}`} />
                    <Stack spacing={0.5} sx={{ p: 1 }}>
                      <Typography variant="body2" sx={{ fontWeight: 700 }}>
                        {index + 1}. {media.caption || "Uncaptioned"}
                        {media.primaryImage ? " - Primary" : ""}
                      </Typography>
                      {canManage ? (
                        <Stack direction="row" spacing={0.5}>
                          <Button
                            size="small"
                            disabled={index === 0}
                            onClick={() => void move(media, -1)}
                          >
                            Earlier
                          </Button>
                          <Button
                            size="small"
                            disabled={index === layouts.length - 1}
                            onClick={() => void move(media, 1)}
                          >
                            Later
                          </Button>
                          <Button size="small" onClick={() => void editLayout(media)}>
                            Caption
                          </Button>
                          <Button
                            size="small"
                            disabled={media.primaryImage}
                            onClick={() => void setPrimary(media)}
                          >
                            Primary
                          </Button>
                          <IconButton
                            aria-label="Remove layout photo"
                            size="small"
                            onClick={() => void remove(media)}
                          >
                            <DeleteIcon fontSize="small" />
                          </IconButton>
                        </Stack>
                      ) : null}
                    </Stack>
                  </Paper>
                ))}
              </Stack>
            )}
          </>
        ) : null}
      </Stack>
    </Paper>
  );
}
