import { AppError, apiClient, toAppError } from "@bigcontainers/api-client";
import type { components } from "@bigcontainers/api-client";

/**
 * The permanent roles from spec section 4.1, derived from the generated
 * `SessionResponse` schema rather than duplicated as a hand-written union.
 */
export type Role = NonNullable<components["schemas"]["SessionResponse"]["role"]>;

/**
 * The backend always populates every field of `SessionResponse` on a
 * successful response; the generated type marks them optional only because
 * the live OpenAPI document carries no `required` metadata for response
 * DTOs (see the api-client package's `errors.ts` comment for the same gap
 * on error responses). `Required<...>` narrows the generated type instead
 * of duplicating its shape.
 */
export type SessionPrincipal = Required<components["schemas"]["SessionResponse"]>;

export type LoginCredentials = components["schemas"]["LoginRequest"];

export type SessionOutcome =
  | { kind: "authenticated"; principal: SessionPrincipal }
  | { kind: "anonymous" }
  | { kind: "error"; error: AppError };

function asPrincipal(
  data: components["schemas"]["SessionResponse"] | undefined,
): SessionPrincipal | undefined {
  if (
    data?.userId !== undefined &&
    data.username !== undefined &&
    data.displayName !== undefined &&
    data.organizationId !== undefined &&
    data.role !== undefined
  ) {
    return data as SessionPrincipal;
  }
  return undefined;
}

/**
 * Restores session state from the server (spec 4.3, ADR-0003). A `401` here
 * is the ordinary "not signed in" outcome, not a failure to be surfaced as
 * an error.
 */
export async function fetchCurrentSession(): Promise<SessionOutcome> {
  try {
    const { data, error, response } = await apiClient.GET("/api/v1/session");
    if (response.status === 401) {
      return { kind: "anonymous" };
    }
    const principal = asPrincipal(data);
    if (error || !response.ok || !principal) {
      return { kind: "error", error: toAppError(error, response.status) };
    }
    return { kind: "authenticated", principal };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}

/** POST /api/v1/session: local username/password login (spec 4.3). */
export async function login(credentials: LoginCredentials): Promise<SessionOutcome> {
  try {
    const { data, error, response } = await apiClient.POST("/api/v1/session", {
      body: credentials,
    });
    const principal = asPrincipal(data);
    if (error || !response.ok || !principal) {
      return { kind: "error", error: toAppError(error, response.status) };
    }
    return { kind: "authenticated", principal };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}

/** DELETE /api/v1/session: idempotent per the backend contract. */
export async function logout(): Promise<AppError | undefined> {
  try {
    const { error, response } = await apiClient.DELETE("/api/v1/session");
    if (!response.ok) {
      return toAppError(error, response.status);
    }
    return undefined;
  } catch (cause) {
    return AppError.network(cause);
  }
}
