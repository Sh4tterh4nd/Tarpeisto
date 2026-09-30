import { useCallback, useEffect, useState } from "react";
import { useSyncExternalStore } from "react";
import { auditPartition } from "../../data/indexeddb/auditDatabase";
import {
  auditOutbox,
  type AuditCommand,
  type QueuedAuditCommand,
} from "../../data/outbox/auditOutbox";
import { AuditSync, verifyLiveAuditActor } from "../../data/sync/auditSync";
import { auditIdentityEpoch, subscribeAuditIdentityStop } from "../../data/sync/auditIdentity";
import { useSession } from "../identity/useSession";
import type { SessionPrincipal } from "../identity/sessionApi";
import { webConnectivityCapability } from "../../platform/web/webConnectivityCapability";
import {
  getAuditContainer,
  getAuditTask,
  type AuditContainer,
  type ContainerAudit,
  type AuditResult,
} from "./auditApi";

export function useAuditQueue(taskId: string) {
  const { principal, status } = useSession();
  const partition = principal ? auditPartition(principal) : undefined;
  const scope = `${partition}/${taskId}`;
  const identityEpoch = auditIdentityEpoch();
  const [loadedScope, setLoadedScope] = useState<string>();
  const [identityGeneration, setIdentityGeneration] = useState<number>();
  const [ready, setReady] = useState<{ scope: string; principal: SessionPrincipal }>();
  const [audit, setAudit] = useState<ContainerAudit>();
  const [container, setContainer] = useState<AuditContainer>();
  const [commands, setCommands] = useState<QueuedAuditCommand[]>([]);
  const [error, setError] = useState<string>();
  const connectivity = useSyncExternalStore(
    webConnectivityCapability.subscribe,
    webConnectivityCapability.getStatus,
  );
  useEffect(() => {
    let active = true;
    const stop = partition
      ? auditOutbox.subscribe(
          partition,
          taskId,
          (value) => {
            if (!active) return;
            setLoadedScope(scope);
            if (value.snapshot) {
              setAudit(value.snapshot.audit);
              setContainer(value.snapshot.container);
            } else {
              setAudit(undefined);
              setContainer(undefined);
            }
            setCommands(value.commands);
          },
          () => setError("Browser storage is unavailable. Work has not been saved."),
        )
      : undefined;
    if (status !== "offline-audit")
      void (async () => {
        const generation = await auditOutbox.identityGeneration().catch(() => 0);
        if (!active) return;
        setIdentityGeneration(generation);
        const revision = partition
          ? ((await auditOutbox.snapshot(partition, taskId).catch(() => undefined))?.revision ?? 0)
          : undefined;
        if (!active) return;
        const result = await getAuditTask(taskId);
        if (!active) return;
        if (result.kind === "error") {
          setError(result.error.problem?.detail ?? result.error.message);
          if (principal && result.error.kind === "network") setReady({ scope, principal });
          return;
        }
        const identity = await getAuditContainer(result.data.containerAssetId);
        if (!active) return;
        const nextContainer = identity.kind === "ok" ? identity.data : undefined;
        if (principal) {
          const snapshot = await auditOutbox
            .cache(principal, result.data, nextContainer, revision, identityEpoch, generation)
            .catch(() => {
              if (active) setError("Browser storage is unavailable. Work has not been saved.");
              return undefined;
            });
          if (!active) return;
          setAudit(snapshot?.audit ?? result.data);
          setContainer(snapshot?.container ?? nextContainer);
        } else {
          setAudit(result.data);
          setContainer(nextContainer);
        }
        setLoadedScope(scope);
        if (active && principal) setReady({ scope, principal });
      })();
    return () => {
      active = false;
      stop?.();
    };
  }, [partition, principal, taskId, status, scope, identityEpoch]);
  useEffect(() => {
    if (
      !principal ||
      status !== "authenticated" ||
      ready?.scope !== scope ||
      ready.principal !== principal
    )
      return;
    const sync = new AuditSync(principal, auditOutbox, setError);
    sync.start();
    return () => sync.stop();
  }, [principal, status, ready, scope]);
  const accept = useCallback(
    async (value: ContainerAudit) => {
      if (!principal) throw new Error("Sign in before recording work.");
      if (identityGeneration === undefined)
        throw new Error("Wait for audit recovery before starting or completing.");
      const snapshot = await auditOutbox.cache(
        principal,
        value,
        container,
        undefined,
        identityEpoch,
        identityGeneration,
      );
      setAudit(snapshot?.audit ?? value);
    },
    [principal, container, identityEpoch, identityGeneration],
  );
  const enqueue = async (command: AuditCommand, bytes?: ArrayBuffer) => {
    if (!partition || !audit) return;
    try {
      const operationId = await auditOutbox.enqueue(partition, audit, command, bytes);
      setError(undefined);
      return operationId;
    } catch (cause) {
      setError(
        cause instanceof Error
          ? `Work has not been saved: ${cause.message}`
          : "Work has not been saved. Browser storage is unavailable.",
      );
    }
  };
  const findingWithPhotos = async (
    command: Extract<AuditCommand, { kind: "finding" }>,
    files: File[],
  ) => {
    if (!partition || !audit) return;
    try {
      const photos = await Promise.all(
        files.map(async (file) => ({
          fileName: file.name,
          contentType: file.type,
          bytes: await file.arrayBuffer(),
        })),
      );
      const operationId = await auditOutbox.findingWithPhotos(partition, audit, command, photos);
      setError(undefined);
      return operationId;
    } catch (cause) {
      setError(
        cause instanceof Error
          ? `Work has not been saved: ${cause.message}`
          : "Work has not been saved. Browser storage is unavailable.",
      );
    }
  };
  const retry = async (row: QueuedAuditCommand) => {
    try {
      await auditOutbox.retry(row);
      setError(undefined);
    } catch {
      setError("Retry could not be saved. The queued work remains available.");
    }
  };
  const cancel = async (row: QueuedAuditCommand) => {
    try {
      await auditOutbox.cancel(row);
      setError(undefined);
      return true;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Cancellation could not be saved.");
      return false;
    }
  };
  const complete = async (
    action: (actor: string, signal: AbortSignal) => Promise<AuditResult<ContainerAudit>>,
  ) => {
    if (
      !principal ||
      !partition ||
      !audit?.id ||
      connectivity === "offline" ||
      status !== "authenticated"
    )
      throw new Error("Connect and verify your session before completing the audit.");
    const lease = await auditOutbox.acquire(partition, "complete");
    if (!lease) throw new Error("Another tab is synchronizing or completing. Wait and retry.");
    const controller = new AbortController();
    const stopIdentity = subscribeAuditIdentityStop(() => controller.abort());
    const heartbeat = setInterval(() => {
      void auditOutbox.heartbeat(lease).catch(() => controller.abort());
    }, 3000);
    try {
      await verifyLiveAuditActor(principal);
      await auditOutbox.completionBarrier(lease, audit.id);
      if (controller.signal.aborted)
        throw new Error("Audit synchronization stopped. Verify your session before completing.");
      const result = await action(partition, controller.signal);
      await auditOutbox.assertLease(lease);
      if (controller.signal.aborted)
        throw new Error(
          "Audit synchronization stopped. Reconnect to recover the authoritative result.",
        );
      if (result.kind === "ok") await accept(result.data);
      return result;
    } finally {
      clearInterval(heartbeat);
      stopIdentity();
      await auditOutbox.release(lease);
    }
  };
  const syncStatus = commands.some((row) => row.state === "failed")
    ? "Failed"
    : connectivity === "offline" || status === "offline-audit"
      ? "Offline"
      : commands.length
        ? "Synchronizing"
        : "Online";
  return {
    audit: loadedScope === scope ? audit : undefined,
    container: loadedScope === scope ? container : undefined,
    commands: loadedScope === scope ? commands : [],
    error,
    setError,
    enqueue,
    findingWithPhotos,
    retry,
    cancel,
    accept,
    complete,
    syncStatus,
    completionBlocked:
      commands.length > 0 || connectivity === "offline" || status === "offline-audit",
  };
}
