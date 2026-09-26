import { useSyncExternalStore } from "react";
import type { ConnectivityCapability, ConnectivityStatus } from "./ConnectivityCapability";

/** Subscribes to a {@link ConnectivityCapability} and returns its current status. */
export function useConnectivity(capability: ConnectivityCapability): ConnectivityStatus {
  return useSyncExternalStore(capability.subscribe, capability.getStatus, capability.getStatus);
}
