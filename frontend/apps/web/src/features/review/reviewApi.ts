import { AppError, apiClient, toAppError } from "@tarpeisto/api-client";
import type { components, ProblemDetails } from "@tarpeisto/api-client";

export type FindingReview = Required<components["schemas"]["FindingReviewResponse"]>;
export type ResolutionAction = components["schemas"]["ResolveFindingRequest"]["action"];
export type ApiResult<T> = { kind: "ok"; data: T } | { kind: "error"; error: AppError };

async function read<T>(
  operation: () => Promise<{ data?: unknown; error?: ProblemDetails; response: Response }>,
): Promise<ApiResult<T>> {
  try {
    const { data, error, response } = await operation();
    if (error || !response.ok || data === undefined)
      return { kind: "error", error: toAppError(error, response.status) };
    return { kind: "ok", data: data as T };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}

export function listFindings(unresolvedOnly = true) {
  return read<FindingReview[]>(() =>
    apiClient.GET("/api/v1/findings", { params: { query: { unresolvedOnly } } }),
  );
}
export function getFinding(findingId: string) {
  return read<FindingReview>(() =>
    apiClient.GET("/api/v1/findings/{findingId}", { params: { path: { findingId } } }),
  );
}
export function resolveFinding(
  findingId: string,
  body: components["schemas"]["ResolveFindingRequest"],
) {
  return read<FindingReview>(() =>
    apiClient.POST("/api/v1/findings/{findingId}/resolutions", {
      params: { path: { findingId } },
      body,
    }),
  );
}
export function reviewError(error: AppError) {
  return error.problem?.detail ?? error.message;
}
