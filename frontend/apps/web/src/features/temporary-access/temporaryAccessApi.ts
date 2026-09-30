import { apiClient, AppError, toAppError, type components } from "@tarpeisto/api-client";
import type { AuditResult } from "../audits/auditApi";
import type { SessionPrincipal } from "../identity/sessionApi";

export type TemporaryInvitation = Required<components["schemas"]["TemporaryAccessInvitation"]>;
export type IssuedInvitation = Required<components["schemas"]["TemporaryInvitationResponse"]>;
export type AssignedAuditTask = Required<components["schemas"]["AssignedAuditTaskView"]>;
export type InvitationScope = Pick<
  components["schemas"]["CreateTemporaryInvitationRequest"],
  "bookingId" | "auditBatchId"
>;

export async function createInvitation(
  scope: InvitationScope,
): Promise<AuditResult<IssuedInvitation>> {
  try {
    const { data, error, response } = await apiClient.POST("/api/v1/temporary-access/invitations", {
      body: { ...scope, joinUrl: `${window.location.origin}/join` },
    });
    return response.ok && data
      ? { kind: "ok", data: data as IssuedInvitation }
      : { kind: "error", error: toAppError(error, response.status) };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}
export async function listInvitations(
  scope: InvitationScope,
): Promise<AuditResult<TemporaryInvitation[]>> {
  try {
    const { data, error, response } = await apiClient.GET("/api/v1/temporary-access/invitations", {
      params: { query: scope },
    });
    return response.ok && data
      ? { kind: "ok", data: data as TemporaryInvitation[] }
      : { kind: "error", error: toAppError(error, response.status) };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}
export async function revokeInvitation(invitationId: string): Promise<AuditResult<void>> {
  try {
    const { error, response } = await apiClient.POST(
      "/api/v1/temporary-access/invitations/{invitationId}/revoke",
      { params: { path: { invitationId } } },
    );
    return response.ok
      ? { kind: "ok", data: undefined }
      : { kind: "error", error: toAppError(error, response.status) };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}
export async function redeemInvitation(
  token: string,
  displayName: string,
  operationId: string,
): Promise<AuditResult<SessionPrincipal>> {
  try {
    const { data, error, response } = await apiClient.POST("/api/v1/temporary-access/redemptions", {
      body: { token, displayName, operationId },
    });
    return response.ok && data
      ? { kind: "ok", data: data as SessionPrincipal }
      : { kind: "error", error: toAppError(error, response.status) };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}
export async function listAssignedTasks(): Promise<AuditResult<AssignedAuditTask[]>> {
  try {
    const { data, error, response } = await apiClient.GET("/api/v1/temporary-access/tasks");
    return response.ok && data
      ? { kind: "ok", data: data as AssignedAuditTask[] }
      : { kind: "error", error: toAppError(error, response.status) };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}
