import Dexie, { type Table } from "dexie";
import type { components } from "@tarpeisto/api-client";
import type { AuditContainer, ContainerAudit } from "../../features/audits/auditApi";
import type { SessionPrincipal } from "../../features/identity/sessionApi";

type Schemas = components["schemas"];
export type AuditCommand =
  | { kind: "scan"; body: Schemas["ScanAuditRequest"] }
  | { kind: "move"; body: Schemas["ScanAuditRequest"] }
  | { kind: "undo"; scanId: string; body: Schemas["UndoAuditScanRequest"] }
  | { kind: "finding"; body: Schemas["RecordAuditFindingRequest"] }
  | { kind: "consumable"; expectedId: string; body: Schemas["ObserveAuditConsumableRequest"] }
  | {
      kind: "photo";
      findingId?: string;
      findingOperationId?: string;
      fileName: string;
      contentType: string;
    };
export interface QueuedAuditCommand {
  sequence?: number;
  operationId: string;
  partition: string;
  auditId: string;
  taskId: string;
  command: AuditCommand;
  state: "pending" | "sending" | "failed";
  attempts: number;
  nextAttemptAt: number;
  error?: string;
  progress: number;
}
export interface CachedAudit {
  temporaryExpiresAt?: string;
  revision?: number;
  key: string;
  partition: string;
  taskId: string;
  audit: ContainerAudit;
  container?: AuditContainer;
}
export interface AuditLease {
  partition: string;
  token: string;
  expiresAt: number;
  mode: "drain" | "complete";
}
export interface CachedAuditIdentity {
  generation?: number;
  id: string;
  principal?: SessionPrincipal;
  taskId?: string;
  enabled: boolean;
}
export class AuditDatabase extends Dexie {
  commands!: Table<QueuedAuditCommand, number>;
  snapshots!: Table<CachedAudit, string>;
  blobs!: Table<{ operationId: string; bytes: ArrayBuffer }, string>;
  leases!: Table<AuditLease, string>;
  identities!: Table<CachedAuditIdentity, string>;
  constructor(name = "tarpeisto-active-audit-v1") {
    super(name);
    this.version(1).stores({
      commands: "++sequence,&operationId,partition,[partition+auditId]",
      snapshots: "key,partition,taskId",
      blobs: "operationId",
      leases: "partition",
      identities: "id",
    });
  }
}
export const auditDatabase = new AuditDatabase();
export const auditPartition = (principal: Pick<SessionPrincipal, "organizationId" | "userId">) =>
  `${principal.organizationId}:${principal.userId}`;
