/**
 * Browser-independent camera and QR decoding contract. Scanner UI depends on
 * this small capability rather than DOM camera APIs so unsupported devices and
 * decoder fallbacks can be tested without a physical camera.
 */
export type QrScannerAvailability = "ready" | "unsupported" | "insecure";

export type QrScannerEngine = "native" | "fallback";

export type QrScannerSignal = "reconnecting";

export interface QrCameraDevice {
  readonly deviceId: string;
  readonly label: string;
}

export interface QrScanSession {
  readonly engine: QrScannerEngine;
  stop(): void;
}

export interface QrScannerCapability {
  availability(): QrScannerAvailability;
  listCameras(): Promise<QrCameraDevice[]>;
  start(
    video: HTMLVideoElement,
    deviceId: string | undefined,
    onCode: (rawCode: string) => void,
    onSignal: (signal: QrScannerSignal) => void,
  ): Promise<QrScanSession>;
}
