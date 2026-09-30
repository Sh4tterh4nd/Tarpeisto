import { apiClient, AppError, toAppError, type ProblemDetails } from "@tarpeisto/api-client";
import { auditPartition, type AuditLease } from "../indexeddb/auditDatabase";
import { auditOutbox, type AuditOutbox, type QueuedAuditCommand } from "../outbox/auditOutbox";
import { fetchCurrentSession, type SessionPrincipal } from "../../features/identity/sessionApi";
import { uploadAuditEvidence } from "../../features/audits/auditEvidenceApi";
import type { AuditResult, ContainerAudit } from "../../features/audits/auditApi";
import { stopAuditIdentity, subscribeAuditIdentityStop } from "./auditIdentity";
import { webConnectivityCapability } from "../../platform/web/webConnectivityCapability";
import { temporaryAccessExpired } from "./temporaryDeadline";

export function temporaryAuditFailure(error: AppError) {
  return (
    error.kind === "network" ||
    error.status === 408 ||
    error.status === 429 ||
    (error.status !== undefined && error.status >= 500)
  );
}
export async function sendAuditCommand(
  row: QueuedAuditCommand,
  signal: AbortSignal,
  outbox: AuditOutbox = auditOutbox,
  progress: (value: number) => void = () => {},
): Promise<AuditResult<ContainerAudit | undefined>> {
  const auditId = row.auditId;
  const command = row.command;
  const headers = { "X-Tarpeisto-Audit-Actor": row.partition };
  if (command.kind === "photo") {
    const snapshot = await outbox.snapshot(row.partition, row.taskId);
    const findingId =
      command.findingId ??
      snapshot?.audit.findings.find(
        (finding) => finding.sourceOperationId === command.findingOperationId,
      )?.id;
    const blob = await outbox.db.blobs.get(row.operationId);
    if (!findingId || !blob)
      return {
        kind: "error",
        error: AppError.fromProblemDetails(
          {
            type: "about:blank",
            title: "Evidence dependency unavailable",
            status: 409,
            detail:
              "The photograph's source finding or saved bytes are unavailable. Retry the source operation or cancel this photograph.",
          },
          409,
        ),
      };
    const result = await uploadAuditEvidence(
      auditId,
      findingId,
      row.operationId,
      row.partition,
      new Blob([blob.bytes], { type: command.contentType }),
      command.fileName,
      progress,
      signal,
    );
    return result.kind === "ok" ? { kind: "ok", data: undefined } : result;
  }
  try {
    const base = { headers, body: command.body, signal };
    const result =
      command.kind === "scan"
        ? await apiClient.POST("/api/v1/audits/{auditId}/scans", {
            ...base,
            body: command.body,
            params: { path: { auditId } },
          })
        : command.kind === "move"
          ? await apiClient.POST("/api/v1/audits/{auditId}/move-code-here", {
              ...base,
              body: command.body,
              params: { path: { auditId } },
            })
          : command.kind === "undo"
            ? await apiClient.POST("/api/v1/audits/{auditId}/scans/{scanId}/undo", {
                ...base,
                body: command.body,
                params: { path: { auditId, scanId: command.scanId } },
              })
            : command.kind === "finding"
              ? await apiClient.POST("/api/v1/audits/{auditId}/findings", {
                  ...base,
                  body: command.body,
                  params: { path: { auditId } },
                })
              : await apiClient.POST("/api/v1/audits/{auditId}/consumables/{expectedId}", {
                  ...base,
                  body: command.body,
                  params: { path: { auditId, expectedId: command.expectedId } },
                });
    return result.response.ok && result.data
      ? { kind: "ok", data: result.data as ContainerAudit }
      : {
          kind: "error",
          error: toAppError(result.error as ProblemDetails | undefined, result.response.status),
        };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}
export async function verifyLiveAuditActor(principal: SessionPrincipal) {
  if (temporaryAccessExpired(principal)) {
    await stopAuditIdentity();
    throw new Error("Temporary access has expired. Ask for a new invitation.");
  }
  const session = await fetchCurrentSession();
  if (
    session.kind !== "authenticated" ||
    auditPartition(session.principal) !== auditPartition(principal)
  ) {
    if (session.kind !== "error") await stopAuditIdentity();
    throw new Error(
      "Sign in with the account that recorded this audit before synchronizing or completing.",
    );
  }
}
export class AuditSync {
  private stopped = false;
  private running = false;
  private controller = new AbortController();
  private timer?: ReturnType<typeof setInterval>;
  private unsubscribe?: () => void;
  private unsubscribeConnectivity?: () => void;
  constructor(
    private principal: SessionPrincipal,
    private outbox: AuditOutbox = auditOutbox,
    private report: (message: string | undefined) => void = () => {},
  ) {}
  start() {
    this.unsubscribe = subscribeAuditIdentityStop(() => this.stop());
    this.timer = setInterval(() => {
      void this.tick();
    }, 1000);
    this.unsubscribeConnectivity = webConnectivityCapability.subscribe(this.online);
    void this.tick();
  }
  private online = () => {
    void this.tick();
  };
  stop() {
    this.stopped = true;
    this.controller.abort();
    clearInterval(this.timer);
    this.unsubscribe?.();
    this.unsubscribeConnectivity?.();
  }
  async tick() {
    if (this.stopped || this.running) return;
    if (temporaryAccessExpired(this.principal)) {
      await stopAuditIdentity(this.outbox.db);
      this.stop();
      this.report("Temporary access has expired. Ask for a new invitation.");
      return;
    }
    if (webConnectivityCapability.getStatus() === "offline") return;
    this.running = true;
    this.controller = new AbortController();
    let lease: AuditLease | undefined;
    let heartbeat: ReturnType<typeof setInterval> | undefined;
    try {
      const partition = auditPartition(this.principal);
      const rows = await this.outbox.list(partition);
      if (!rows.length || rows[0]!.state === "failed" || rows[0]!.nextAttemptAt > Date.now())
        return;
      lease = await this.outbox.acquire(partition, "drain");
      if (!lease) return;
      heartbeat = setInterval(() => {
        if (lease) void this.outbox.heartbeat(lease).catch(() => this.controller.abort());
      }, 3000);
      await verifyLiveAuditActor(this.principal);
      if (this.stopped) return;
      this.report(undefined);
      for (;;) {
        const row = (await this.outbox.list(partition))[0];
        if (
          !row ||
          row.state === "failed" ||
          row.nextAttemptAt > Date.now() ||
          this.stopped ||
          webConnectivityCapability.getStatus() === "offline"
        )
          break;
        await this.outbox.sending(lease, row);
        await this.outbox.assertTemporaryAccess(partition);
        const heldLease = lease;
        const result = await sendAuditCommand(row, this.controller.signal, this.outbox, (value) => {
          void this.outbox.sending(heldLease, row, value).catch(() => this.controller.abort());
        });
        if (this.stopped) break;
        if (result.kind === "ok") await this.outbox.acknowledge(lease, row, result.data);
        else {
          if (result.error.status === 401) {
            await stopAuditIdentity(this.outbox.db);
            break;
          }
          await this.outbox.failure(
            lease,
            row,
            result.error.problem?.detail ?? result.error.message,
            temporaryAuditFailure(result.error),
          );
          break;
        }
      }
    } catch (cause) {
      this.report(cause instanceof Error ? cause.message : "Synchronization is paused.");
    } finally {
      clearInterval(heartbeat);
      if (lease) await this.outbox.release(lease).catch(() => {});
      this.running = false;
    }
  }
}
