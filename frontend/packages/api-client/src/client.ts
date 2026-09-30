import createOpenApiFetchClient from "openapi-fetch";
import type { Client, Middleware } from "openapi-fetch";
import type { paths } from "./generated/backendOpenApi.generated";
import { AppError, type ProblemDetails } from "./errors";
import { CSRF_HEADER_NAME, isMutatingMethod, readCsrfCookie } from "./csrf";

export interface CreateApiClientOptions {
  /**
   * Same-origin base URL. The generated `paths` already include the `/api/v1`
   * prefix (matching the backend's OpenAPI document), so this is normally left
   * as the origin root.
   */
  baseUrl?: string;
  /** Invoked whenever a response comes back 401, so the app shell can react without the client redirecting itself (spec 24.2, ADR-0001). */
  onUnauthenticated?: () => void;
}

export type ApiClient = Client<paths>;

/**
 * The `paths` type's keys already include the full `/api/v1/...` prefix
 * (matching the backend's OpenAPI document), so the default base is the
 * origin itself. A relative empty string resolves correctly in a real
 * browser, but the WHATWG `Request`/`fetch` implementation used outside one
 * (Node, and therefore Vitest under jsdom) requires an absolute URL, so an
 * explicit origin is used whenever `window` is available.
 */
function defaultBaseUrl(): string {
  return typeof window !== "undefined" ? window.location.origin : "";
}

function csrfMiddleware(): Middleware {
  return {
    async onRequest({ request }) {
      if (isMutatingMethod(request.method)) {
        const token = readCsrfCookie();
        if (token) {
          request.headers.set(CSRF_HEADER_NAME, token);
        }
      }
      return request;
    },
  };
}

function unauthenticatedMiddleware(onUnauthenticated?: () => void): Middleware {
  return {
    async onResponse({ response }) {
      if (response.status === 401) {
        onUnauthenticated?.();
      }
      return response;
    },
  };
}

/**
 * Creates the cross-cutting Tarpeisto API client: same-origin,
 * cookie-based session credentials, automatic CSRF header propagation on
 * mutating requests, and a 401 hook for surfacing an unauthenticated state.
 * Error-body mapping is applied per-call with {@link toAppError}, since
 * `openapi-fetch` returns typed `{ data, error }` results rather than
 * throwing.
 */
export function createApiClient(options: CreateApiClientOptions = {}): ApiClient {
  const client = createOpenApiFetchClient<paths>({
    baseUrl: options.baseUrl ?? defaultBaseUrl(),
    credentials: "include",
    // Resolve `fetch` lazily on every call rather than capturing
    // `globalThis.fetch` once when the client is constructed. `apiClient`
    // below is a module-level singleton created at import time, so an early
    // capture would permanently miss a `fetch` mock/polyfill installed
    // afterwards (as tests do).
    fetch: (input) => globalThis.fetch(input),
  });

  client.use(csrfMiddleware());
  client.use(unauthenticatedMiddleware(options.onUnauthenticated));

  return client;
}

type UnauthenticatedListener = () => void;

const unauthenticatedListeners = new Set<UnauthenticatedListener>();

export function notifyUnauthenticated(): void {
  for (const listener of unauthenticatedListeners) {
    listener();
  }
}

/**
 * Subscribes to 401 responses observed by the shared {@link apiClient}
 * singleton. The identity feature's session context uses this to notice a
 * session that has expired or been revoked mid-use (for example a stale
 * cookie on a mutating call) and return the user to sign-in, without the
 * transport layer itself owning navigation. Returns an unsubscribe function.
 */
export function onUnauthenticatedResponse(listener: UnauthenticatedListener): () => void {
  unauthenticatedListeners.add(listener);
  return () => unauthenticatedListeners.delete(listener);
}

/** Ready-to-use client for the common same-origin case described in ADR-0001. */
export const apiClient: ApiClient = createApiClient({ onUnauthenticated: notifyUnauthenticated });

/**
 * The single error-mapping layer: turns an `openapi-fetch` error result
 * (already known, from the response status, to be a Problem Details body)
 * into a typed {@link AppError}.
 */
export function toAppError(error: ProblemDetails | undefined, status: number): AppError {
  if (error) {
    return AppError.fromProblemDetails(error, status);
  }
  return AppError.unknown(status);
}
