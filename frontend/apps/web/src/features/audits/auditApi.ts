import { AppError, apiClient, toAppError } from "@tarpeisto/api-client";
import type { components, ProblemDetails } from "@tarpeisto/api-client";

type GeneratedExpected = components["schemas"]["AuditExpectedRequirementResponse"];
type GeneratedScan = components["schemas"]["AuditScanResponse"];
type GeneratedFinding = components["schemas"]["AuditFindingResponse"];
type GeneratedAudit = components["schemas"]["ContainerAuditResponse"];
type GeneratedAsset = Required<components["schemas"]["AuditContainerResponse"]>;

export type AuditExpectedRequirement = Omit<
  Required<GeneratedExpected>,
  "assetModelId" | "specificAssetId" | "requiredQuantity" | "matchedQuantity"
> &
  Pick<
    GeneratedExpected,
    "assetModelId" | "specificAssetId" | "requiredQuantity" | "matchedQuantity"
  >;
export type AuditScan = Omit<
  Required<GeneratedScan>,
  "recordedByUserId" | "recordedByDisplayName" | "assetModelId"
> &
  Partial<GeneratedScan>;
export type AuditFinding = Omit<
  Required<GeneratedFinding>,
  "assetId" | "note" | "sourceOperationId" | "recordedByUserId" | "recordedByDisplayName"
> &
  Partial<GeneratedFinding>;
export type ContainerAudit = Omit<
  Required<GeneratedAudit>,
  | "id"
  | "completionOutcome"
  | "expectedRequirements"
  | "scans"
  | "findings"
  | "archived"
  | "archiveVersion"
> &
  Pick<GeneratedAudit, "id" | "completionOutcome" | "archived" | "archiveVersion"> & {
    expectedRequirements: AuditExpectedRequirement[];
    scans: AuditScan[];
    findings: AuditFinding[];
  };
export type AuditFindingType = components["schemas"]["RecordAuditFindingRequest"]["type"];
export type AuditConsumableStatus =
  components["schemas"]["ObserveAuditConsumableRequest"]["status"];
export type AuditContainer = Pick<GeneratedAsset, "id" | "displayName" | "publicCode"> &
  Partial<Pick<GeneratedAsset, "sealable">>;
export type AuditManualCandidate = Required<components["schemas"]["AuditManualCandidateResponse"]>;
export type AuditManualCandidatePage = Omit<
  Required<components["schemas"]["AuditManualCandidatePageResponse"]>,
  "items" | "nextCursor"
> & { items: AuditManualCandidate[]; nextCursor?: string };
export type AuditResult<T> = { kind: "ok"; data: T } | { kind: "error"; error: AppError };

interface Outcome {
  data?: unknown;
  error?: ProblemDetails;
  response: Response;
}

async function read<T>(operation: () => Promise<Outcome>): Promise<AuditResult<T>> {
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
export const getAuditTask = (taskId: string) =>
  read<ContainerAudit>(() =>
    apiClient.GET("/api/v1/audits/tasks/{taskId}", { params: { path: { taskId } } }),
  );
export const getAuditContainer = (taskId: string) =>
  read<AuditContainer>(() =>
    apiClient.GET("/api/v1/audits/tasks/{taskId}/container", { params: { path: { taskId } } }),
  );
export const startAudit = (taskId: string, containerCode: string) =>
  read<ContainerAudit>(() =>
    apiClient.POST("/api/v1/audits/tasks/{taskId}/start", {
      params: { path: { taskId } },
      body: { containerCode },
    }),
  );
export const scanAudit = (auditId: string, code: string, operationId = crypto.randomUUID()) =>
  read<ContainerAudit>(() =>
    apiClient.POST("/api/v1/audits/{auditId}/scans", {
      params: { path: { auditId } },
      body: { operationId, code },
    }),
  );
export const moveAuditScanHere = (
  auditId: string,
  code: string,
  operationId = crypto.randomUUID(),
) =>
  read<ContainerAudit>(() =>
    apiClient.POST("/api/v1/audits/{auditId}/move-code-here", {
      params: { path: { auditId } },
      body: { operationId, code },
    }),
  );
export const undoAuditScan = (auditId: string, scanId: string, operationId = crypto.randomUUID()) =>
  read<ContainerAudit>(() =>
    apiClient.POST("/api/v1/audits/{auditId}/scans/{scanId}/undo", {
      params: { path: { auditId, scanId } },
      body: { operationId },
    }),
  );
export const recordAuditFinding = (
  auditId: string,
  type: AuditFindingType,
  assetId?: string,
  note?: string,
  operationId = crypto.randomUUID(),
) =>
  read<ContainerAudit>(() =>
    apiClient.POST("/api/v1/audits/{auditId}/findings", {
      params: { path: { auditId } },
      body: { operationId, type, assetId, note },
    }),
  );
export const observeAuditConsumable = (
  auditId: string,
  expectedId: string,
  status: AuditConsumableStatus,
  observedQuantity?: number,
  reason?: string,
  operationId = crypto.randomUUID(),
) =>
  read<ContainerAudit>(() =>
    apiClient.POST("/api/v1/audits/{auditId}/consumables/{expectedId}", {
      params: { path: { auditId, expectedId } },
      body: { operationId, status, observedQuantity, reason },
    }),
  );
export const completeAudit = (
  auditId: string,
  containerCode: string,
  confirmMissing: boolean,
  sealConfirmed: boolean,
  operationId = crypto.randomUUID(),
  actor?: string,
  signal?: AbortSignal,
) =>
  read<ContainerAudit>(() =>
    apiClient.POST("/api/v1/audits/{auditId}/complete", {
      signal,
      headers: actor ? { "X-Tarpeisto-Audit-Actor": actor } : undefined,
      params: { path: { auditId } },
      body: { operationId, containerCode, confirmMissing, sealConfirmed },
    }),
  );

export const getAuditManualCandidates = (auditId: string, query?: string, cursor?: string) =>
  read<AuditManualCandidatePage>(() =>
    apiClient.GET("/api/v1/audits/{auditId}/manual-candidates", {
      params: { path: { auditId }, query: { query, cursor, limit: 100 } },
    }),
  );
