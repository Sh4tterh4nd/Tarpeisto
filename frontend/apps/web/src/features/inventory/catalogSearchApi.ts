import {
  apiClient,
  AppError,
  toAppError,
  type components,
  type paths,
} from "@tarpeisto/api-client";
export type ModelSearchRecord = Required<components["schemas"]["ModelSearchResponse"]>;
export type StockSearchRecord = Omit<Required<components["schemas"]["StockSearchResponse"]>, "id"> &
  Pick<components["schemas"]["StockSearchResponse"], "id">;
type ModelSearchPage = Omit<
  Required<components["schemas"]["ModelSearchPageResponse"]>,
  "items" | "nextCursor"
> & { items: ModelSearchRecord[] } & Pick<
    components["schemas"]["ModelSearchPageResponse"],
    "nextCursor"
  >;
type StockSearchPage = Omit<
  Required<components["schemas"]["StockSearchPageResponse"]>,
  "items" | "nextCursor"
> & { items: StockSearchRecord[] } & Pick<
    components["schemas"]["StockSearchPageResponse"],
    "nextCursor"
  >;
type Result<T> = { kind: "ok"; data: T } | { kind: "error"; error: AppError };
export async function searchModels(
  query: NonNullable<paths["/api/v1/asset-models/search"]["get"]["parameters"]["query"]>,
): Promise<Result<ModelSearchPage>> {
  try {
    const { data, error, response } = await apiClient.GET("/api/v1/asset-models/search", {
      params: { query },
    });
    return response.ok && data
      ? { kind: "ok", data: data as ModelSearchPage }
      : { kind: "error", error: toAppError(error, response.status) };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}
export async function searchStock(
  query: NonNullable<paths["/api/v1/consumable-stock/search"]["get"]["parameters"]["query"]>,
): Promise<Result<StockSearchPage>> {
  try {
    const { data, error, response } = await apiClient.GET("/api/v1/consumable-stock/search", {
      params: { query },
    });
    return response.ok && data
      ? { kind: "ok", data: data as StockSearchPage }
      : { kind: "error", error: toAppError(error, response.status) };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}
