import { auditDatabase, auditPartition, type AuditDatabase } from "../indexeddb/auditDatabase";
import type { SessionPrincipal } from "../../features/identity/sessionApi";
import { temporaryAccessExpired } from "./temporaryDeadline";

type IdentityStopReason = "stop" | "account-switch";
const listeners = new Set<(reason: IdentityStopReason) => void>();
let channel: BroadcastChannel | undefined;
let identityEpoch = 0;
export const auditIdentityEpoch = () => identityEpoch;
function notify(reason: IdentityStopReason = "stop") {
  identityEpoch++;
  for (const listener of listeners) listener(reason);
}
export function subscribeAuditIdentityStop(listener: (reason: IdentityStopReason) => void) {
  listeners.add(listener);
  if (!channel && typeof BroadcastChannel !== "undefined") {
    channel = new BroadcastChannel("tarpeisto-audit-identity");
    channel.onmessage = () => notify("stop");
  }
  return () => {
    listeners.delete(listener);
    if (!listeners.size) {
      channel?.close();
      channel = undefined;
    }
  };
}
export async function stopAuditIdentity(db: AuditDatabase = auditDatabase) {
  notify();
  channel?.postMessage("stop");
  await disableAuditRecovery(db);
}
export async function disableAuditRecovery(db: AuditDatabase = auditDatabase) {
  await db.transaction("rw", db.identities, async () => {
    const previous = await db.identities.get("last-audit");
    await db.identities.put({
      ...previous,
      id: "last-audit",
      enabled: false,
      generation: (previous?.generation ?? 0) + 1,
    });
  });
}
export async function recoverAuditIdentity() {
  const identity = await auditDatabase.identities.get("last-audit");
  if (!identity?.enabled || !identity.taskId || !identity.principal) return undefined;
  if (temporaryAccessExpired(identity.principal)) {
    await stopAuditIdentity();
    return undefined;
  }
  const snapshot = await auditDatabase.snapshots.get(
    `${auditPartition(identity.principal)}/${identity.taskId}`,
  );
  return snapshot?.audit.state === "IN_PROGRESS"
    ? { ...identity, principal: identity.principal }
    : undefined;
}
export async function verifyAuditIdentity(principal: SessionPrincipal) {
  if (temporaryAccessExpired(principal)) {
    await stopAuditIdentity();
    return;
  }
  const previous = await auditDatabase.identities.get("last-audit");
  if (previous?.principal && auditPartition(previous.principal) !== auditPartition(principal)) {
    notify("account-switch");
    channel?.postMessage("stop");
    await disableAuditRecovery();
  }
}
