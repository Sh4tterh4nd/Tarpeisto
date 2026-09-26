import type {
  ConnectivityCapability,
  ConnectivityStatus,
} from "../capabilities/ConnectivityCapability";

/**
 * Web implementation of {@link ConnectivityCapability}, backed by
 * `navigator.onLine` and the `online`/`offline` window events. This is the
 * only file allowed to touch those browser APIs directly; everything else
 * goes through the capability interface.
 */
function createWebConnectivityCapability(): ConnectivityCapability {
  function getStatus(): ConnectivityStatus {
    if (typeof navigator === "undefined") {
      return "online";
    }
    return navigator.onLine ? "online" : "offline";
  }

  function subscribe(listener: () => void): () => void {
    if (typeof window === "undefined") {
      return () => {};
    }
    window.addEventListener("online", listener);
    window.addEventListener("offline", listener);
    return () => {
      window.removeEventListener("online", listener);
      window.removeEventListener("offline", listener);
    };
  }

  return { getStatus, subscribe };
}

export const webConnectivityCapability: ConnectivityCapability = createWebConnectivityCapability();
