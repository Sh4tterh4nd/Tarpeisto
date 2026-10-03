import { AppError, apiClient, toAppError } from "@tarpeisto/api-client";
import type { components, paths, ProblemDetails } from "@tarpeisto/api-client";

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
export type AssetRecord = Omit<
  Required<components["schemas"]["AssetResponse"]>,
  "values" | "containerColor" | "unitDescription" | "version"
> &
  Pick<components["schemas"]["AssetResponse"], "containerColor" | "unitDescription" | "version"> & {
    values: AssetValueRecord[];
  };
export type AssetHistoryRecord = Required<components["schemas"]["AssetStateChangeResponse"]>;
export type StockBalanceRecord = Omit<
  Required<components["schemas"]["ConsumableStockResponse"]>,
  "archived" | "version"
> &
  Pick<components["schemas"]["ConsumableStockResponse"], "archived" | "version">;
export type StockSummaryRecord = Required<components["schemas"]["AssetModelStockSummaryResponse"]>;
export type StockMovementRecord = Required<components["schemas"]["StockMovementResponse"]>;
export type LocationRecord = Required<components["schemas"]["LocationResponse"]>;
export type AssetPlacementRecord = Required<components["schemas"]["AssetPlacementResponse"]>;
export type AssetSearchRecord = Omit<
  Required<components["schemas"]["AssetSearchResponse"]>,
  "effectiveLocationId" | "placePath"
> &
  Pick<components["schemas"]["AssetSearchResponse"], "effectiveLocationId" | "placePath">;
export type AssetSearchPage = Omit<
  Required<components["schemas"]["AssetSearchPageResponse"]>,
  "items"
> & {
  items: AssetSearchRecord[];
};

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

export function listCategories(includeArchived = false): Promise<ApiResult<CategoryRecord[]>> {
  return read(() =>
    apiClient.GET("/api/v1/categories", { params: { query: { includeArchived } } }),
  );
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

export function deleteCategory(categoryId: string) {
  return command(() =>
    apiClient.DELETE("/api/v1/categories/{categoryId}", { params: { path: { categoryId } } }),
  );
}

export function listAssetModels(includeArchived = false): Promise<ApiResult<AssetModelRecord[]>> {
  return read(() =>
    apiClient.GET("/api/v1/asset-models", { params: { query: { includeArchived } } }),
  );
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

export function changeAssetModelCategory(assetModelId: string, categoryId?: string) {
  return read<AssetModelRecord>(() =>
    apiClient.PUT("/api/v1/asset-models/{assetModelId}/category", {
      params: { path: { assetModelId } },
      body: { categoryId },
    }),
  );
}

export function searchAssets(
  query: NonNullable<paths["/api/v1/assets"]["get"]["parameters"]["query"]>,
): Promise<ApiResult<AssetSearchPage>> {
  return read(() => apiClient.GET("/api/v1/assets", { params: { query } }));
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

export function listCustomFields(
  assetModelId: string,
  includeArchived = false,
): Promise<ApiResult<CustomFieldRecord[]>> {
  return read(() =>
    apiClient.GET("/api/v1/asset-models/{assetModelId}/custom-fields", {
      params: { path: { assetModelId }, query: { includeArchived } },
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
  includeArchived = false,
): Promise<ApiResult<CustomFieldOptionRecord[]>> {
  return read(() =>
    apiClient.GET("/api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/options", {
      params: { path: { assetModelId, fieldId }, query: { includeArchived } },
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

export function setAssetPackingDetails(
  assetId: string,
  body: components["schemas"]["SetAssetPackingDetailsRequest"],
) {
  return read<AssetRecord>(() =>
    apiClient.PUT("/api/v1/assets/{assetId}/packing-details", {
      params: { path: { assetId } },
      body,
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

export function listLocations(includeArchived = false): Promise<ApiResult<LocationRecord[]>> {
  return read(() => apiClient.GET("/api/v1/locations", { params: { query: { includeArchived } } }));
}

export function createLocation(input: components["schemas"]["LocationRequest"]) {
  return read<LocationRecord>(() => apiClient.POST("/api/v1/locations", { body: input }));
}

export function updateLocation(
  locationId: string,
  input: components["schemas"]["LocationRequest"],
) {
  return read<LocationRecord>(() =>
    apiClient.PUT("/api/v1/locations/{locationId}", {
      params: { path: { locationId } },
      body: input,
    }),
  );
}

export function setLocationArchived(
  locationId: string,
  archived: boolean,
  expectedVersion: number,
) {
  const path = archived
    ? "/api/v1/locations/{locationId}/archive"
    : "/api/v1/locations/{locationId}/restore";
  return command(() =>
    apiClient.POST(path, { params: { path: { locationId } }, body: { expectedVersion } }),
  );
}

export function getAssetPlacement(assetId: string): Promise<ApiResult<AssetPlacementRecord>> {
  return read(() =>
    apiClient.GET("/api/v1/assets/{assetId}/placement", { params: { path: { assetId } } }),
  );
}

export function moveAsset(
  assetId: string,
  input: components["schemas"]["AssetPlacementRequest"],
): Promise<ApiResult<AssetPlacementRecord>> {
  return read(() =>
    apiClient.PUT("/api/v1/assets/{assetId}/placement", {
      params: { path: { assetId } },
      body: input,
    }),
  );
}

export function listContainerContents(assetId: string): Promise<ApiResult<AssetPlacementRecord[]>> {
  return read(() =>
    apiClient.GET("/api/v1/assets/{assetId}/contents", { params: { path: { assetId } } }),
  );
}

export function listContainerStock(assetId: string): Promise<ApiResult<StockBalanceRecord[]>> {
  return read(() =>
    apiClient.GET("/api/v1/assets/{assetId}/consumable-stock", { params: { path: { assetId } } }),
  );
}

export function listLocationStock(locationId: string): Promise<ApiResult<StockBalanceRecord[]>> {
  return read(() =>
    apiClient.GET("/api/v1/locations/{locationId}/consumable-stock", {
      params: { path: { locationId } },
    }),
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
export type StockPlaceInput = { containerAssetId?: string; locationId?: string };
export type PackingRequirementRecord = Required<
  components["schemas"]["PackingRequirementResponse"]
>;
export type PackingTemplateRecord = Omit<
  Required<components["schemas"]["PackingTemplateResponse"]>,
  "requirements"
> & { requirements: PackingRequirementRecord[] };
export type PackingRequirementInput = components["schemas"]["PackingRequirementRequest"];
export type PackingTemplateInput = components["schemas"]["PackingTemplateRequest"];
export type ConsumablePackingStatus = Required<
  NonNullable<components["schemas"]["PackingPreviewResponse"]["consumables"]>[number]
>;
export type PackingPreviewRecord = Omit<
  Required<components["schemas"]["PackingPreviewResponse"]>,
  "consumables"
> & { consumables: ConsumablePackingStatus[] };
export type PackingRequirementMutationInput =
  components["schemas"]["PackingRequirementMutationRequest"];
export type PackingTemplateMutationInput = components["schemas"]["PackingTemplateMutationRequest"];

export function listPackingRequirements(
  containerAssetId: string,
): Promise<ApiResult<PackingRequirementRecord[]>> {
  return read(() =>
    apiClient.GET("/api/v1/assets/{containerAssetId}/packing-requirements", {
      params: { path: { containerAssetId } },
    }),
  );
}
export function listPackingTemplates(
  includeArchived = false,
): Promise<ApiResult<PackingTemplateRecord[]>> {
  return read(() =>
    apiClient.GET("/api/v1/packing-templates", { params: { query: { includeArchived } } }),
  );
}

export function addPackingRequirement(containerAssetId: string, input: PackingRequirementInput) {
  return read<PackingRequirementRecord>(() =>
    apiClient.POST("/api/v1/assets/{containerAssetId}/packing-requirements", {
      params: { path: { containerAssetId } },
      body: input,
    }),
  );
}

export function archivePackingRequirement(
  id: string,
  expectedVersion: number,
  confirmAffectedBookings = false,
) {
  return command(() =>
    apiClient.POST("/api/v1/packing-requirements/{id}/archive", {
      params: { path: { id } },
      body: { expectedVersion, confirmAffectedBookings },
    }),
  );
}

export function restorePackingRequirement(id: string, expectedVersion: number) {
  return read<PackingRequirementRecord>(() =>
    apiClient.POST("/api/v1/packing-requirements/{id}/restore", {
      params: { path: { id } },
      body: { expectedVersion },
    }),
  );
}
export function updatePackingRequirement(id: string, input: PackingRequirementMutationInput) {
  return read<PackingRequirementRecord>(() =>
    apiClient.PUT("/api/v1/packing-requirements/{id}", { params: { path: { id } }, body: input }),
  );
}

export function createPackingTemplate(input: PackingTemplateInput) {
  return read<PackingTemplateRecord>(() =>
    apiClient.POST("/api/v1/packing-templates", { body: input }),
  );
}
export function updatePackingTemplate(templateId: string, input: PackingTemplateMutationInput) {
  return read<PackingTemplateRecord>(() =>
    apiClient.PUT("/api/v1/packing-templates/{templateId}", {
      params: { path: { templateId } },
      body: input,
    }),
  );
}
export function setPackingTemplateArchived(
  templateId: string,
  expectedVersion: number,
  action: "archive" | "restore",
) {
  return read<PackingTemplateRecord>(() =>
    apiClient.POST("/api/v1/packing-templates/{templateId}/{action}", {
      params: { path: { templateId, action } },
      body: { expectedVersion },
    }),
  );
}

export function addPackingTemplateRequirement(templateId: string, input: PackingRequirementInput) {
  return read<PackingTemplateRecord>(() =>
    apiClient.POST("/api/v1/packing-templates/{templateId}/requirements", {
      params: { path: { templateId } },
      body: input,
    }),
  );
}
export function updatePackingTemplateRequirement(
  id: string,
  input: PackingRequirementMutationInput,
) {
  return read<PackingTemplateRecord>(() =>
    apiClient.PUT("/api/v1/packing-template-requirements/{id}", {
      params: { path: { id } },
      body: input,
    }),
  );
}
export function setPackingTemplateRequirementArchived(
  id: string,
  expectedVersion: number,
  action: "archive" | "restore",
) {
  return read<PackingTemplateRecord>(() =>
    apiClient.POST("/api/v1/packing-template-requirements/{id}/{action}", {
      params: { path: { id, action } },
      body: { expectedVersion },
    }),
  );
}

export function applyPackingTemplate(containerAssetId: string, templateId: string) {
  return read<PackingRequirementRecord[]>(() =>
    apiClient.POST("/api/v1/assets/{containerAssetId}/packing-templates/{templateId}/apply", {
      params: { path: { containerAssetId, templateId } },
    }),
  );
}

export function previewPacking(
  containerAssetId: string,
  observedConsumableQuantities: Record<string, number> = {},
) {
  return read<PackingPreviewRecord>(() =>
    apiClient.POST("/api/v1/assets/{containerAssetId}/packing-preview", {
      params: { path: { containerAssetId } },
      body: { observedConsumableQuantities },
    }),
  );
}

export function changeStock(
  assetModelId: string,
  action: StockAction,
  place: StockPlaceInput,
  quantity: number,
  note?: string,
) {
  const body = { ...place, quantity, note };
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
  place: StockPlaceInput,
  delta: number,
  reason: string,
) {
  return read<StockBalanceRecord>(() =>
    apiClient.POST("/api/v1/asset-models/{assetModelId}/consumable-stock/adjust", {
      params: { path: { assetModelId } },
      body: { ...place, delta, type: "MANUAL_ADJUSTMENT", reason },
    }),
  );
}

export function transferStock(
  assetModelId: string,
  source: StockPlaceInput,
  destination: StockPlaceInput,
  quantity: number,
  note?: string,
) {
  return read<Required<components["schemas"]["StockTransferResponse"]>>(() =>
    apiClient.POST("/api/v1/asset-models/{assetModelId}/consumable-stock/transfer", {
      params: { path: { assetModelId } },
      body: {
        sourceContainerAssetId: source.containerAssetId,
        sourceLocationId: source.locationId,
        destinationContainerAssetId: destination.containerAssetId,
        destinationLocationId: destination.locationId,
        quantity,
        note,
      },
    }),
  );
}

export type PackingContentIdentity = Required<
  components["schemas"]["PackingContentIdentityResponse"]
>;
export type PackingContentEntry = Omit<
  Required<components["schemas"]["PackingContentEntryResponse"]>,
  "asset"
> & { asset: PackingContentIdentity };
export type PackingContentRequirement = Omit<
  Required<components["schemas"]["PackingContentRequirementResponse"]>,
  "exactAsset"
> & { exactAsset?: PackingContentIdentity };
export type PackingContentConsumable = Required<
  components["schemas"]["PackingContentConsumableResponse"]
>;
export type PackingContents = Omit<
  Required<components["schemas"]["PackingContentsResponse"]>,
  "assets" | "requirements" | "consumables"
> & {
  assets: PackingContentEntry[];
  requirements: PackingContentRequirement[];
  consumables: PackingContentConsumable[];
};
export type RepairRecord = Required<components["schemas"]["RepairResponse"]>;
export type SealHistoryRecord = Required<components["schemas"]["SealHistoryView"]>;

export function getPackingContents(assetId: string, cursor?: string) {
  return read<PackingContents>(() =>
    apiClient.GET("/api/v1/assets/{assetId}/packing-contents", {
      params: { path: { assetId }, query: { cursor } },
    }),
  );
}
export function listAssetRepairs(assetId: string) {
  return read<RepairRecord[]>(() =>
    apiClient.GET("/api/v1/assets/{assetId}/repairs", { params: { path: { assetId } } }),
  );
}
export function listSealHistory(assetId: string) {
  return read<SealHistoryRecord[]>(() =>
    apiClient.GET("/api/v1/assets/{assetId}/seal/history", { params: { path: { assetId } } }),
  );
}
export function openAssetRepair(assetId: string, referenceOrDescription: string) {
  return read<RepairRecord>(() =>
    apiClient.POST("/api/v1/assets/{assetId}/repairs", {
      params: { path: { assetId } },
      body: { referenceOrDescription },
    }),
  );
}
export function closeAssetRepair(repairId: string, resultingCondition: "GOOD" | "DAMAGED") {
  return read<RepairRecord>(() =>
    apiClient.POST("/api/v1/repairs/{repairId}/close", {
      params: { path: { repairId } },
      body: { resultingCondition },
    }),
  );
}
export function createAssetReplacement(assetId: string, individualName?: string) {
  return read<AssetRecord>(() =>
    apiClient.POST("/api/v1/assets/{assetId}/replacement", {
      params: { path: { assetId } },
      body: { individualName, values: [] },
    }),
  );
}
export function setAssetSealable(assetId: string, sealable: boolean) {
  return command(() =>
    apiClient.PUT("/api/v1/assets/{assetId}/sealable", {
      params: { path: { assetId } },
      body: { sealable },
    }),
  );
}
export function applyAssetSeal(assetId: string, input: components["schemas"]["ApplySealRequest"]) {
  return command(() =>
    apiClient.POST("/api/v1/assets/{assetId}/seal/apply", {
      params: { path: { assetId } },
      body: input,
    }),
  );
}
export function breakAssetSeal(assetId: string, note?: string) {
  return command(() =>
    apiClient.POST("/api/v1/assets/{assetId}/seal/break", {
      params: { path: { assetId } },
      body: { note },
    }),
  );
}
