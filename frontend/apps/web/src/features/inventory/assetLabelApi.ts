import { AppError, apiClient, toAppError } from "@bigcontainers/api-client";
import type { components } from "@bigcontainers/api-client";

export type AssetLabelPdfInput = components["schemas"]["CreateAssetLabelPdfRequest"];
export type AssetLabelCalibrationInput =
  components["schemas"]["CreateAssetLabelCalibrationRequest"];
export type PtouchCsvInput = components["schemas"]["CreatePtouchCsvRequest"];

export type AssetLabelDownload = { kind: "ok"; data: Blob } | { kind: "error"; error: AppError };

async function download(
  operation: () => Promise<{ data?: Blob; response: Response }>,
): Promise<AssetLabelDownload> {
  try {
    const { data, response } = await operation();
    if (!response.ok || !data) {
      return { kind: "error", error: toAppError(undefined, response.status) };
    }
    return { kind: "ok", data };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}

/** Downloads a generated A4 label sheet through the generated OpenAPI operation. */
export function createAssetLabelPdf(input: AssetLabelPdfInput): Promise<AssetLabelDownload> {
  return download(() =>
    apiClient.POST("/api/v1/asset-labels/pdf", { body: input, parseAs: "blob" }),
  );
}

/** Downloads an alignment page for the selected stock and calibration values. */
export function createAssetLabelCalibration(
  input: AssetLabelCalibrationInput,
): Promise<AssetLabelDownload> {
  return download(() =>
    apiClient.POST("/api/v1/asset-labels/calibration", { body: input, parseAs: "blob" }),
  );
}

/** Downloads the generated Brother P-touch CSV using the server's stable columns. */
export function createPtouchCsv(input: PtouchCsvInput): Promise<AssetLabelDownload> {
  return download(() =>
    apiClient.POST("/api/v1/asset-labels/ptouch-csv", { body: input, parseAs: "blob" }),
  );
}

/** Saves a server-produced blob without reconstructing or interpreting its document content. */
export function downloadAssetLabelFile(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.style.display = "none";
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
