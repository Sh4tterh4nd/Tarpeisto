import { AppError, apiClient, toAppError } from "@bigcontainers/api-client";
import type { components, ProblemDetails } from "@bigcontainers/api-client";

// The generated OpenAPI marks response members optional because Springdoc does not emit
// `required` for Java record accessors. These endpoints always serialize their declared response
// records (and `read` rejects an absent body), so the UI's domain records intentionally model that
// server guarantee rather than adding undefined checks to every rendered value.
export type CategoryRecord = Required<components["schemas"]["CategoryResponse"]>;
export type AssetModelRecord = Required<components["schemas"]["AssetModelResponse"]>;
export type CustomFieldRecord = Required<components["schemas"]["ModelCustomFieldResponse"]>;
export type CustomFieldOptionRecord = Required<
  components["schemas"]["ModelCustomFieldOptionResponse"]
>;
export type AssetValueRecord = Required<components["schemas"]["AssetCustomFieldValueResponse"]>;
export type AssetRecord = Omit<Required<components["schemas"]["AssetResponse"]>, "values"> & {
  values: AssetValueRecord[];
};
export type AssetHistoryRecord = Required<components["schemas"]["AssetStateChangeResponse"]>;
export type StockBalanceRecord = Required<components["schemas"]["ConsumableStockResponse"]>;
export type StockSummaryRecord = Required<components["schemas"]["AssetModelStockSummaryResponse"]>;
export type StockMovementRecord = Required<components["schemas"]["StockMovementResponse"]>;

export type CreateCategoryInput = components["schemas"]["CreateCategoryRequest"];
export type CreateAssetModelInput = components["schemas"]["CreateAssetModelRequest"];
export type CreateCustomFieldInput = components["schemas"]["CreateModelCustomFieldRequest"];
export type CreateAssetInput = components["schemas"]["CreateAssetRequest"];
export type AssetValueInput = components["schemas"]["AssetCustomFieldValueRequest"];
export type StockMovementReason = NonNullable<StockMovementRecord["reason"]>;

export type ApiResult<T> = { kind: "ok"; data: T } | { kind: "error"; error: AppError };

interface OpenApiOutcome {
  data?: unknown;
  error?: ProblemDetails;
  response: Response;
}

async function read<T>(operation: () => Promise<OpenApiOutcome>): Promise<ApiResult<T>> {
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

async function command(operation: () => Promise<OpenApiOutcome>): Promise<AppError | undefined> {
  try {
    const { error, response } = await operation();
    return response.ok ? undefined : toAppError(error, response.status);
  } catch (cause) {
    return AppError.network(cause);
  }
}

export function errorMessage(error: AppError): string {
  return error.problem?.detail ?? error.message;
}

export function listCategories(): Promise<ApiResult<CategoryRecord[]>> {
  return read(() => apiClient.GET("/api/v1/categories"));
}

export function createCategory(input: CreateCategoryInput): Promise<ApiResult<CategoryRecord>> {
  return read(() => apiClient.POST("/api/v1/categories", { body: input }));
}

export function updateCategory(
  categoryId: string,
  input: CreateCategoryInput,
): Promise<ApiResult<CategoryRecord>> {
  return read(() =>
    apiClient.PUT("/api/v1/categories/{categoryId}", {
      params: { path: { categoryId } },
      body: input,
    }),
  );
}

export function setCategoryArchived(categoryId: string, archived: boolean) {
  const path = archived
    ? "/api/v1/categories/{categoryId}/archive"
    : "/api/v1/categories/{categoryId}/restore";
  return command(() => apiClient.POST(path, { params: { path: { categoryId } } }));
}

export function listAssetModels(): Promise<ApiResult<AssetModelRecord[]>> {
  return read(() => apiClient.GET("/api/v1/asset-models"));
}

export function getAssetModel(assetModelId: string): Promise<ApiResult<AssetModelRecord>> {
  return read(() =>
    apiClient.GET("/api/v1/asset-models/{assetModelId}", {
      params: { path: { assetModelId } },
    }),
  );
}

export function createAssetModel(
  input: CreateAssetModelInput,
): Promise<ApiResult<AssetModelRecord>> {
  return read(() => apiClient.POST("/api/v1/asset-models", { body: input }));
}

export function updateAssetModelDetails(assetModelId: string, name: string, description?: string) {
  return read<AssetModelRecord>(() =>
    apiClient.PUT("/api/v1/asset-models/{assetModelId}", {
      params: { path: { assetModelId } },
      body: { name, description },
    }),
  );
}

export function changeAssetModelCategory(assetModelId: string, categoryId: string) {
  return read<AssetModelRecord>(() =>
    apiClient.PUT("/api/v1/asset-models/{assetModelId}/category", {
      params: { path: { assetModelId } },
      body: { categoryId },
    }),
  );
}

export function changeAssetModelReplacementUrl(assetModelId: string, replacementUrl?: string) {
  return read<AssetModelRecord>(() =>
    apiClient.PUT("/api/v1/asset-models/{assetModelId}/replacement-url", {
      params: { path: { assetModelId } },
      body: { replacementUrl },
    }),
  );
}

export function changeAssetModelTrackingMode(
  assetModelId: string,
  trackingMode: AssetModelRecord["trackingMode"],
  stockUnitLabel?: string,
  lowStockThreshold?: number,
) {
  return read<AssetModelRecord>(() =>
    apiClient.PUT("/api/v1/asset-models/{assetModelId}/tracking-mode", {
      params: { path: { assetModelId } },
      body: { trackingMode, stockUnitLabel, lowStockThreshold },
    }),
  );
}

export function setAssetModelCanContainAssets(assetModelId: string, canContainAssets: boolean) {
  return read<AssetModelRecord>(() =>
    apiClient.PUT("/api/v1/asset-models/{assetModelId}/can-contain-assets", {
      params: { path: { assetModelId } },
      body: { canContainAssets },
    }),
  );
}

export function setAssetModelArchived(assetModelId: string, archived: boolean) {
  const path = archived
    ? "/api/v1/asset-models/{assetModelId}/archive"
    : "/api/v1/asset-models/{assetModelId}/restore";
  return command(() => apiClient.POST(path, { params: { path: { assetModelId } } }));
}

export function listCustomFields(assetModelId: string): Promise<ApiResult<CustomFieldRecord[]>> {
  return read(() =>
    apiClient.GET("/api/v1/asset-models/{assetModelId}/custom-fields", {
      params: { path: { assetModelId } },
    }),
  );
}

export function createCustomField(
  assetModelId: string,
  input: CreateCustomFieldInput,
): Promise<ApiResult<CustomFieldRecord>> {
  return read(() =>
    apiClient.POST("/api/v1/asset-models/{assetModelId}/custom-fields", {
      params: { path: { assetModelId } },
      body: input,
    }),
  );
}

export function renameCustomField(assetModelId: string, fieldId: string, name: string) {
  return read<CustomFieldRecord>(() =>
    apiClient.PUT("/api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}", {
      params: { path: { assetModelId, fieldId } },
      body: { name },
    }),
  );
}

export function changeCustomFieldDataType(
  assetModelId: string,
  fieldId: string,
  dataType: NonNullable<CustomFieldRecord["dataType"]>,
) {
  return read<CustomFieldRecord>(() =>
    apiClient.PUT("/api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/data-type", {
      params: { path: { assetModelId, fieldId } },
      body: { dataType },
    }),
  );
}

export function reorderCustomField(assetModelId: string, fieldId: string, displayOrder: number) {
  return read<CustomFieldRecord>(() =>
    apiClient.PUT("/api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/display-order", {
      params: { path: { assetModelId, fieldId } },
      body: { displayOrder },
    }),
  );
}

export function setCustomFieldArchived(assetModelId: string, fieldId: string, archived: boolean) {
  const path = archived
    ? "/api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/archive"
    : "/api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/restore";
  return command(() => apiClient.POST(path, { params: { path: { assetModelId, fieldId } } }));
}

export function listCustomFieldOptions(
  assetModelId: string,
  fieldId: string,
): Promise<ApiResult<CustomFieldOptionRecord[]>> {
  return read(() =>
    apiClient.GET("/api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/options", {
      params: { path: { assetModelId, fieldId } },
    }),
  );
}

export function createCustomFieldOption(assetModelId: string, fieldId: string, value: string) {
  return read<CustomFieldOptionRecord>(() =>
    apiClient.POST("/api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/options", {
      params: { path: { assetModelId, fieldId } },
      body: { value },
    }),
  );
}

export function renameCustomFieldOption(
  assetModelId: string,
  fieldId: string,
  optionId: string,
  value: string,
) {
  return read<CustomFieldOptionRecord>(() =>
    apiClient.PUT(
      "/api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/options/{optionId}",
      {
        params: { path: { assetModelId, fieldId, optionId } },
        body: { value },
      },
    ),
  );
}

export function reorderCustomFieldOption(
  assetModelId: string,
  fieldId: string,
  optionId: string,
  displayOrder: number,
) {
  return read<CustomFieldOptionRecord>(() =>
    apiClient.PUT(
      "/api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/options/{optionId}/display-order",
      {
        params: { path: { assetModelId, fieldId, optionId } },
        body: { displayOrder },
      },
    ),
  );
}

export function setCustomFieldOptionArchived(
  assetModelId: string,
  fieldId: string,
  optionId: string,
  archived: boolean,
) {
  const path = archived
    ? "/api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/options/{optionId}/archive"
    : "/api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/options/{optionId}/restore";
  return command(() =>
    apiClient.POST(path, { params: { path: { assetModelId, fieldId, optionId } } }),
  );
}

export function listAssets(
  assetModelId: string,
  includeInactive: boolean,
): Promise<ApiResult<AssetRecord[]>> {
  return read(() =>
    apiClient.GET("/api/v1/asset-models/{assetModelId}/assets", {
      params: { path: { assetModelId }, query: { includeInactive } },
    }),
  );
}

export function getAsset(assetId: string): Promise<ApiResult<AssetRecord>> {
  return read(() => apiClient.GET("/api/v1/assets/{assetId}", { params: { path: { assetId } } }));
}

export function findAssetByCode(rawCode: string): Promise<ApiResult<AssetRecord>> {
  return read(() =>
    apiClient.GET("/api/v1/assets/by-code/{rawCode}", { params: { path: { rawCode } } }),
  );
}

export function createAsset(
  assetModelId: string,
  input: CreateAssetInput,
): Promise<ApiResult<AssetRecord>> {
  return read(() =>
    apiClient.POST("/api/v1/asset-models/{assetModelId}/assets", {
      params: { path: { assetModelId } },
      body: input,
    }),
  );
}

export function createAssetsBulk(assetModelId: string, count: number, purchaseDate?: string) {
  return read<AssetRecord[]>(() =>
    apiClient.POST("/api/v1/asset-models/{assetModelId}/assets/bulk", {
      params: { path: { assetModelId } },
      body: { count, purchaseDate },
    }),
  );
}

export function setAssetValues(assetId: string, values: AssetValueInput[]) {
  return read<AssetRecord>(() =>
    apiClient.PUT("/api/v1/assets/{assetId}/values", {
      params: { path: { assetId } },
      body: values,
    }),
  );
}

export function renameAsset(assetId: string, individualName?: string) {
  return read<AssetRecord>(() =>
    apiClient.PUT("/api/v1/assets/{assetId}/name", {
      params: { path: { assetId } },
      body: { individualName },
    }),
  );
}

export function setAssetPurchaseDate(assetId: string, purchaseDate?: string) {
  return read<AssetRecord>(() =>
    apiClient.PUT("/api/v1/assets/{assetId}/purchase-date", {
      params: { path: { assetId } },
      body: { purchaseDate },
    }),
  );
}

export function setAssetArchived(assetId: string, archived: boolean) {
  const path = archived ? "/api/v1/assets/{assetId}/archive" : "/api/v1/assets/{assetId}/restore";
  return command(() => apiClient.POST(path, { params: { path: { assetId } } }));
}

export function changeAssetCondition(
  assetId: string,
  condition: "GOOD" | "DAMAGED",
  reason?: string,
) {
  return read<AssetRecord>(() =>
    apiClient.PUT("/api/v1/assets/{assetId}/condition", {
      params: { path: { assetId } },
      body: { condition, reason },
    }),
  );
}

export function changeAssetLifecycle(
  assetId: string,
  lifecycleState: "ACTIVE" | "LOST" | "DESTROYED" | "RETIRED",
  reason?: string,
) {
  return read<AssetRecord>(() =>
    apiClient.PUT("/api/v1/assets/{assetId}/lifecycle", {
      params: { path: { assetId } },
      body: { lifecycleState, reason },
    }),
  );
}

export function listAssetHistory(assetId: string): Promise<ApiResult<AssetHistoryRecord[]>> {
  return read(() =>
    apiClient.GET("/api/v1/assets/{assetId}/history", { params: { path: { assetId } } }),
  );
}

export function getStockSummary(assetModelId: string): Promise<ApiResult<StockSummaryRecord>> {
  return read(() =>
    apiClient.GET("/api/v1/asset-models/{assetModelId}/consumable-stock/summary", {
      params: { path: { assetModelId } },
    }),
  );
}

export function listStockBalances(assetModelId: string): Promise<ApiResult<StockBalanceRecord[]>> {
  return read(() =>
    apiClient.GET("/api/v1/asset-models/{assetModelId}/consumable-stock", {
      params: { path: { assetModelId } },
    }),
  );
}

export function listStockMovements(balanceId: string): Promise<ApiResult<StockMovementRecord[]>> {
  return read(() =>
    apiClient.GET("/api/v1/consumable-stock/{balanceId}/movements", {
      params: { path: { balanceId } },
    }),
  );
}

export type StockAction = "receive" | "issue" | "return" | "consume";

export function changeStock(
  assetModelId: string,
  action: StockAction,
  containerAssetId: string,
  quantity: number,
  note?: string,
) {
  const body = { containerAssetId, quantity, note };
  if (action === "receive") {
    return read<StockBalanceRecord>(() =>
      apiClient.POST("/api/v1/asset-models/{assetModelId}/consumable-stock/receive", {
        params: { path: { assetModelId } },
        body,
      }),
    );
  }
  if (action === "issue") {
    return read<StockBalanceRecord>(() =>
      apiClient.POST("/api/v1/asset-models/{assetModelId}/consumable-stock/issue", {
        params: { path: { assetModelId } },
        body,
      }),
    );
  }
  if (action === "return") {
    return read<StockBalanceRecord>(() =>
      apiClient.POST("/api/v1/asset-models/{assetModelId}/consumable-stock/return", {
        params: { path: { assetModelId } },
        body,
      }),
    );
  }
  return read<StockBalanceRecord>(() =>
    apiClient.POST("/api/v1/asset-models/{assetModelId}/consumable-stock/consume", {
      params: { path: { assetModelId } },
      body,
    }),
  );
}

export function adjustStock(
  assetModelId: string,
  containerAssetId: string,
  delta: number,
  reason: string,
) {
  return read<StockBalanceRecord>(() =>
    apiClient.POST("/api/v1/asset-models/{assetModelId}/consumable-stock/adjust", {
      params: { path: { assetModelId } },
      body: { containerAssetId, delta, type: "MANUAL_ADJUSTMENT", reason },
    }),
  );
}

export function transferStock(
  assetModelId: string,
  sourceContainerAssetId: string,
  destinationContainerAssetId: string,
  quantity: number,
  note?: string,
) {
  return read<Required<components["schemas"]["StockTransferResponse"]>>(() =>
    apiClient.POST("/api/v1/asset-models/{assetModelId}/consumable-stock/transfer", {
      params: { path: { assetModelId } },
      body: { sourceContainerAssetId, destinationContainerAssetId, quantity, note },
    }),
  );
}
