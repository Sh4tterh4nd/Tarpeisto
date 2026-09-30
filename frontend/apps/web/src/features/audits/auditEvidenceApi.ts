import {
  apiClient,
  AppError,
  toAppError,
  readCsrfCookie,
  CSRF_HEADER_NAME,
  notifyUnauthenticated,
  type components,
  type ProblemDetails,
} from "@tarpeisto/api-client";
import type { AuditResult } from "./auditApi";
export type AuditEvidence = Required<components["schemas"]["MediaResponse"]>;
export async function listAuditEvidence(auditId: string): Promise<AuditResult<AuditEvidence[]>> {
  try {
    const result = await apiClient.GET("/api/v1/audits/{auditId}/evidence", {
      params: { path: { auditId } },
    });
    return result.response.ok && result.data
      ? { kind: "ok", data: result.data as AuditEvidence[] }
      : {
          kind: "error",
          error: toAppError(result.error as ProblemDetails | undefined, result.response.status),
        };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}
export async function listFindingEvidence(
  findingId: string,
): Promise<AuditResult<AuditEvidence[]>> {
  try {
    const result = await apiClient.GET("/api/v1/findings/{findingId}/evidence", {
      params: { path: { findingId } },
    });
    return result.response.ok && result.data
      ? { kind: "ok", data: result.data as AuditEvidence[] }
      : {
          kind: "error",
          error: toAppError(result.error as ProblemDetails | undefined, result.response.status),
        };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}
export function uploadAuditEvidence(
  auditId: string,
  findingId: string,
  operationId: string,
  actor: string,
  file: Blob,
  fileName: string,
  progress: (value: number) => void,
  signal: AbortSignal,
): Promise<AuditResult<AuditEvidence>> {
  return new Promise((resolve) => {
    const request = new XMLHttpRequest();
    const abort = () => request.abort();
    const settle = (result: AuditResult<AuditEvidence>) => {
      signal.removeEventListener("abort", abort);
      resolve(result);
    };
    request.open(
      "POST",
      `/api/v1/audits/${encodeURIComponent(auditId)}/findings/${encodeURIComponent(findingId)}/evidence?operationId=${encodeURIComponent(operationId)}`,
    );
    request.timeout = 60_000;
    request.withCredentials = true;
    request.setRequestHeader("X-Tarpeisto-Audit-Actor", actor);
    const csrf = readCsrfCookie();
    if (csrf) request.setRequestHeader(CSRF_HEADER_NAME, csrf);
    request.upload.onprogress = (event) => {
      if (event.lengthComputable) progress(Math.round((event.loaded / event.total) * 100));
    };
    request.onerror = request.ontimeout = () =>
      settle({
        kind: "error",
        error: AppError.network(new Error("Photograph upload interrupted.")),
      });
    request.onabort = () =>
      settle({
        kind: "error",
        error: AppError.network(new Error("Synchronization stopped; photograph remains saved.")),
      });
    request.onload = () => {
      let body: unknown;
      try {
        body = JSON.parse(request.responseText);
      } catch {
        body = undefined;
      }
      if (request.status === 401) notifyUnauthenticated();
      settle(
        request.status >= 200 && request.status < 300 && body
          ? { kind: "ok", data: body as AuditEvidence }
          : {
              kind: "error",
              error: toAppError(body as ProblemDetails | undefined, request.status),
            },
      );
    };
    signal.addEventListener("abort", abort, { once: true });
    if (signal.aborted) {
      request.abort();
      settle({ kind: "error", error: AppError.network(new Error("Synchronization stopped.")) });
      return;
    }
    const form = new FormData();
    form.append("file", file, fileName);
    request.send(form);
  });
}
