/**
 * Connectivity states the UI must be able to show (spec 24.2): the device is
 * online, offline, actively synchronizing queued work, or a sync attempt has
 * failed. Phase 0 only distinguishes online/offline; `synchronizing` and
 * `failed` are reserved for the future outbox/sync layer (policy 6.2,
 * `data/outbox`) to report through this same interface.
 */
export type ConnectivityStatus = "online" | "offline" | "synchronizing" | "failed";

/**
 * Capability interface for connectivity (policy 6.2: "QR scanning, camera,
 * connectivity, and installation use capability interfaces with web
 * implementations"). Components consume this through {@link useConnectivity}
 * rather than calling `navigator.onLine` themselves.
 */
export interface ConnectivityCapability {
  getStatus(): ConnectivityStatus;
  /** Registers a listener invoked whenever the status may have changed; returns an unsubscribe function. */
  subscribe(listener: () => void): () => void;
}
