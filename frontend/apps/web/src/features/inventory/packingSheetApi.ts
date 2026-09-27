import { AppError, apiClient, toAppError } from "@bigcontainers/api-client";

export type PackingSheetDownload = { kind: "ok"; data: Blob } | { kind: "error"; error: AppError };

/** Downloads the server-rendered A4 packing sheet; browser code never rebuilds its contents. */
export async function downloadPackingSheet(assetId: string): Promise<PackingSheetDownload> {
  try {
    const { data, error, response } = await apiClient.GET(
      "/api/v1/assets/{assetId}/packing-sheet.pdf",
      {
        params: { path: { assetId } },
        parseAs: "blob",
      },
    );
    if (!response.ok || !data) return { kind: "error", error: toAppError(error, response.status) };
    return { kind: "ok", data };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}

/** Saves the exact generated PDF in the browser without interpreting or mutating it. */
export function savePackingSheet(blob: Blob, assetId: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `packing-sheet-${assetId}.pdf`;
  anchor.style.display = "none";
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
