import { AppError, apiClient, toAppError } from "@tarpeisto/api-client";
import type { components, ProblemDetails } from "@tarpeisto/api-client";

export type ScannerAsset = Required<components["schemas"]["AssetResponse"]>;
export type ScannerPlacement = Required<components["schemas"]["AssetPlacementResponse"]>;
export type ScannerStockBalance = Required<components["schemas"]["ConsumableStockResponse"]>;
export type ScannerAssetModel = Required<components["schemas"]["AssetModelResponse"]>;

export type ScannerApiResult<T> = { kind: "ok"; data: T } | { kind: "error"; error: AppError };

interface OpenApiOutcome {
  data?: unknown;
  error?: ProblemDetails;
  response: Response;
}

async function read<T>(operation: () => Promise<OpenApiOutcome>): Promise<ScannerApiResult<T>> {
  try {
    const { data, error, response } = await operation();
    if (error || !response.ok || data === undefined) {
      return { kind: "error", error: toAppError(error, response.status) };
    }
    return { kind: "ok", data: data as T };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}

/** Organization-scoped public-code lookup used by both camera and manual scanner input. */
export function lookupScannedAsset(code: string): Promise<ScannerApiResult<ScannerAsset>> {
  return read(() =>
    apiClient.GET("/api/v1/assets/by-code/{rawCode}", { params: { path: { rawCode: code } } }),
  );
}

export function getScannedAsset(assetId: string): Promise<ScannerApiResult<ScannerAsset>> {
  return read(() => apiClient.GET("/api/v1/assets/{assetId}", { params: { path: { assetId } } }));
}

export function getScannedAssetModel(
  assetModelId: string,
): Promise<ScannerApiResult<ScannerAssetModel>> {
  return read(() =>
    apiClient.GET("/api/v1/asset-models/{assetModelId}", {
      params: { path: { assetModelId } },
    }),
  );
}

export function getScannedAssetPlacement(
  assetId: string,
): Promise<ScannerApiResult<ScannerPlacement>> {
  return read(() =>
    apiClient.GET("/api/v1/assets/{assetId}/placement", { params: { path: { assetId } } }),
  );
}

export function listScannedContainerContents(
  assetId: string,
): Promise<ScannerApiResult<ScannerPlacement[]>> {
  return read(() =>
    apiClient.GET("/api/v1/assets/{assetId}/contents", { params: { path: { assetId } } }),
  );
}

export function listScannedContainerStock(
  assetId: string,
): Promise<ScannerApiResult<ScannerStockBalance[]>> {
  return read(() =>
    apiClient.GET("/api/v1/assets/{assetId}/consumable-stock", { params: { path: { assetId } } }),
  );
}

export function restoreScannedAsset(assetId: string): Promise<ScannerApiResult<ScannerAsset>> {
  return read(() =>
    apiClient.PUT("/api/v1/assets/{assetId}/lifecycle", {
      params: { path: { assetId } },
      body: { lifecycleState: "ACTIVE", reason: "Restored after scanner lookup" },
    }),
  );
}

export function scannerErrorMessage(error: AppError): string {
  return error.problem?.detail ?? error.message;
}
