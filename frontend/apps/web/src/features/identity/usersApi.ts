import { AppError, apiClient, toAppError } from "@bigcontainers/api-client";
import type { components } from "@bigcontainers/api-client";
import type { Role } from "./sessionApi";

/**
 * Owner-only user administration and external-identity linking (spec 4.1,
 * 4.3; ADR-0003; implementation plan 3.2). The backend always populates
 * every field of these response DTOs; the generated types mark them optional
 * only because the captured OpenAPI document carries no `required`
 * metadata for response schemas (see `sessionApi.ts` and the api-client
 * package's `errors.ts` for the same gap). `Required<...>` narrows the
 * generated type rather than duplicating its shape.
 */
export type UserRecord = Required<components["schemas"]["UserResponse"]>;
export type CreateUserInput = components["schemas"]["CreateUserRequest"];
export type ExternalIdentityRecord = Required<components["schemas"]["ExternalIdentityResponse"]>;
export type LinkExternalIdentityInput = components["schemas"]["CreateExternalIdentityLinkRequest"];

export type ApiResult<T> = { kind: "ok"; data: T } | { kind: "error"; error: AppError };

/** GET /api/v1/users: Owner-only (spec 4.1). */
export async function listUsers(): Promise<ApiResult<UserRecord[]>> {
  try {
    const { data, error, response } = await apiClient.GET("/api/v1/users");
    // Read the status before the guard below narrows anything -- see the
    // note on this exact pattern in ApplicationInfoPage.tsx and sessionApi.ts.
    const status = response.status;
    if (error || !response.ok || !data) {
      return { kind: "error", error: toAppError(error, status) };
    }
    return { kind: "ok", data: data as UserRecord[] };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}

/** POST /api/v1/users: Owner-only (spec 4.1). */
export async function createUser(input: CreateUserInput): Promise<ApiResult<UserRecord>> {
  try {
    const { data, error, response } = await apiClient.POST("/api/v1/users", { body: input });
    const status = response.status;
    if (error || !response.ok || !data) {
      return { kind: "error", error: toAppError(error, status) };
    }
    return { kind: "ok", data: data as UserRecord };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}

/**
 * PUT /api/v1/users/{userId}/role: Owner-only. May fail with `400
 * VALIDATION_FAILED` ("At least one enabled Owner must remain in the
 * organization.") when demoting the last enabled Owner (spec 4.1/28).
 */
export async function changeUserRole(userId: string, role: Role): Promise<AppError | undefined> {
  try {
    const { error, response } = await apiClient.PUT("/api/v1/users/{userId}/role", {
      params: { path: { userId } },
      body: { role },
    });
    const status = response.status;
    if (!response.ok) {
      return toAppError(error, status);
    }
    return undefined;
  } catch (cause) {
    return AppError.network(cause);
  }
}

/**
 * PUT /api/v1/users/{userId}/enabled: Owner-only. Same last-Owner protection
 * as {@link changeUserRole} applies to disabling the last enabled Owner.
 */
export async function setUserEnabled(
  userId: string,
  enabled: boolean,
): Promise<AppError | undefined> {
  try {
    const { error, response } = await apiClient.PUT("/api/v1/users/{userId}/enabled", {
      params: { path: { userId } },
      body: { enabled },
    });
    const status = response.status;
    if (!response.ok) {
      return toAppError(error, status);
    }
    return undefined;
  } catch (cause) {
    return AppError.network(cause);
  }
}

/** GET /api/v1/users/{userId}/external-identities: Owner-only (ADR-0003). */
export async function listExternalIdentities(
  userId: string,
): Promise<ApiResult<ExternalIdentityRecord[]>> {
  try {
    const { data, error, response } = await apiClient.GET(
      "/api/v1/users/{userId}/external-identities",
      { params: { path: { userId } } },
    );
    const status = response.status;
    if (error || !response.ok || !data) {
      return { kind: "error", error: toAppError(error, status) };
    }
    return { kind: "ok", data: data as ExternalIdentityRecord[] };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}

/**
 * POST /api/v1/users/{userId}/external-identities: Owner-only link by the
 * durable `(issuer, subject)` pair, never email (spec 4.3).
 */
export async function linkExternalIdentity(
  userId: string,
  input: LinkExternalIdentityInput,
): Promise<ApiResult<ExternalIdentityRecord>> {
  try {
    const { data, error, response } = await apiClient.POST(
      "/api/v1/users/{userId}/external-identities",
      { params: { path: { userId } }, body: input },
    );
    const status = response.status;
    if (error || !response.ok || !data) {
      return { kind: "error", error: toAppError(error, status) };
    }
    return { kind: "ok", data: data as ExternalIdentityRecord };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}

/**
 * DELETE /api/v1/users/{userId}/external-identities/{externalIdentityId}:
 * Owner-only. Unlinking the last authentication method of the last Owner
 * returns the same `400 VALIDATION_FAILED` shape as {@link changeUserRole}.
 */
export async function unlinkExternalIdentity(
  userId: string,
  externalIdentityId: string,
): Promise<AppError | undefined> {
  try {
    const { error, response } = await apiClient.DELETE(
      "/api/v1/users/{userId}/external-identities/{externalIdentityId}",
      { params: { path: { userId, externalIdentityId } } },
    );
    const status = response.status;
    if (!response.ok) {
      return toAppError(error, status);
    }
    return undefined;
  } catch (cause) {
    return AppError.network(cause);
  }
}
