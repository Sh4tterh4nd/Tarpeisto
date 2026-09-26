import { BrowserQRCodeReader } from "@zxing/browser";
import type {
  QrCameraDevice,
  QrScanSession,
  QrScannerAvailability,
  QrScannerCapability,
  QrScannerSignal,
} from "../capabilities/QrScannerCapability";

interface BarcodeDetectionResult {
  readonly rawValue: string;
}

interface NativeBarcodeDetector {
  detect(source: ImageBitmapSource): Promise<BarcodeDetectionResult[]>;
}

interface NativeBarcodeDetectorConstructor {
  new (options?: { formats?: string[] }): NativeBarcodeDetector;
  getSupportedFormats?: () => Promise<string[]>;
}

type BarcodeDetectorGlobal = typeof globalThis & {
  BarcodeDetector?: NativeBarcodeDetectorConstructor;
};

function cameraConstraints(deviceId: string | undefined): MediaStreamConstraints {
  return {
    audio: false,
    video: deviceId ? { deviceId: { exact: deviceId } } : { facingMode: { ideal: "environment" } },
  };
}

function stopVideo(video: HTMLVideoElement): void {
  const stream = video.srcObject;
  if (stream && typeof (stream as MediaStream).getTracks === "function") {
    (stream as MediaStream).getTracks().forEach((track) => track.stop());
  }
  video.pause();
  video.srcObject = null;
}

/** Web implementation with BarcodeDetector as a fast path and ZXing as the portable fallback. */
export class WebQrScannerCapability implements QrScannerCapability {
  availability(): QrScannerAvailability {
    if (typeof window === "undefined" || !window.isSecureContext) return "insecure";
    return navigator.mediaDevices ? "ready" : "unsupported";
  }

  async listCameras(): Promise<QrCameraDevice[]> {
    const devices = await navigator.mediaDevices.enumerateDevices();
    return devices
      .filter((device) => device.kind === "videoinput")
      .map((device, index) => ({
        deviceId: device.deviceId,
        label: device.label || `Camera ${index + 1}`,
      }));
  }

  async start(
    video: HTMLVideoElement,
    deviceId: string | undefined,
    onCode: (rawCode: string) => void,
    onSignal: (signal: QrScannerSignal) => void,
  ): Promise<QrScanSession> {
    if (this.availability() !== "ready") {
      throw new Error("Camera scanning is not available in this browser context.");
    }

    const Detector = (globalThis as BarcodeDetectorGlobal).BarcodeDetector;
    if (Detector && (await this.supportsQr(Detector))) {
      try {
        return await this.startNative(
          video,
          deviceId,
          new Detector({ formats: ["qr_code"] }),
          onCode,
          onSignal,
        );
      } catch (error) {
        // Permission and device errors need their native browser message; a
        // decoder constructor failure has already been handled below.
        if (error instanceof DOMException) throw error;
      }
    }
    return this.startFallback(video, deviceId, onCode, onSignal);
  }

  private async supportsQr(Detector: NativeBarcodeDetectorConstructor): Promise<boolean> {
    try {
      const formats = await Detector.getSupportedFormats?.();
      return formats === undefined || formats.includes("qr_code");
    } catch {
      return false;
    }
  }

  private async startNative(
    video: HTMLVideoElement,
    deviceId: string | undefined,
    detector: NativeBarcodeDetector,
    onCode: (rawCode: string) => void,
    onSignal: (signal: QrScannerSignal) => void,
  ): Promise<QrScanSession> {
    const stream = await navigator.mediaDevices.getUserMedia(cameraConstraints(deviceId));
    let stopped = false;
    let frame = 0;
    try {
      stream.getVideoTracks().forEach((track) => {
        track.addEventListener("ended", () => {
          if (!stopped) onSignal("reconnecting");
        });
      });
      video.srcObject = stream;
      await video.play();
    } catch (error) {
      stream.getTracks().forEach((track) => track.stop());
      video.srcObject = null;
      throw error;
    }

    const scan = () => {
      if (stopped) return;
      void detector
        .detect(video)
        .then((codes) => {
          for (const code of codes) {
            if (code.rawValue) onCode(code.rawValue);
          }
        })
        .catch(() => {
          // A frame can be unavailable while a browser starts or switches a
          // camera. The loop continues; setup failures are reported by start.
        })
        .finally(() => {
          if (!stopped) frame = requestAnimationFrame(scan);
        });
    };
    frame = requestAnimationFrame(scan);

    return {
      engine: "native",
      stop: () => {
        stopped = true;
        cancelAnimationFrame(frame);
        stopVideo(video);
      },
    };
  }

  private async startFallback(
    video: HTMLVideoElement,
    deviceId: string | undefined,
    onCode: (rawCode: string) => void,
    onSignal: (signal: QrScannerSignal) => void,
  ): Promise<QrScanSession> {
    const reader = new BrowserQRCodeReader();
    const controls = await reader.decodeFromConstraints(
      cameraConstraints(deviceId),
      video,
      (result, error) => {
        if (result) onCode(result.getText());
        if (error && video.error) onSignal("reconnecting");
      },
    );
    return {
      engine: "fallback",
      stop: () => {
        controls.stop();
        stopVideo(video);
      },
    };
  }
}

export const webQrScannerCapability = new WebQrScannerCapability();
