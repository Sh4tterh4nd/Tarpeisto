import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { WebQrScannerCapability } from "./WebQrScannerCapability";

const zxing = vi.hoisted(() => ({
  decodeFromConstraints: vi.fn(),
  BrowserQRCodeReader: class BrowserQRCodeReader {
    decodeFromConstraints = zxing.decodeFromConstraints;
  },
}));

vi.mock("@zxing/browser", () => ({
  BrowserQRCodeReader: zxing.BrowserQRCodeReader,
}));

function cameraStream() {
  const stop = vi.fn();
  const track = { stop, addEventListener: vi.fn() } as unknown as MediaStreamTrack;
  return {
    stream: {
      getTracks: () => [track],
      getVideoTracks: () => [track],
    } as unknown as MediaStream,
    stop,
  };
}

describe("WebQrScannerCapability", () => {
  let secureContext: PropertyDescriptor | undefined;
  let mediaDevices: PropertyDescriptor | undefined;

  beforeEach(() => {
    secureContext = Object.getOwnPropertyDescriptor(window, "isSecureContext");
    mediaDevices = Object.getOwnPropertyDescriptor(navigator, "mediaDevices");
    Object.defineProperty(window, "isSecureContext", { configurable: true, value: true });
    Object.defineProperty(navigator, "mediaDevices", {
      configurable: true,
      value: { enumerateDevices: vi.fn().mockResolvedValue([]), getUserMedia: vi.fn() },
    });
    zxing.decodeFromConstraints.mockReset();
    zxing.decodeFromConstraints.mockResolvedValue({ stop: vi.fn() });
    vi.stubGlobal(
      "requestAnimationFrame",
      vi.fn(() => 1),
    );
    vi.stubGlobal("cancelAnimationFrame", vi.fn());
  });

  afterEach(() => {
    if (secureContext) Object.defineProperty(window, "isSecureContext", secureContext);
    else Reflect.deleteProperty(window, "isSecureContext");
    if (mediaDevices) Object.defineProperty(navigator, "mediaDevices", mediaDevices);
    else Reflect.deleteProperty(navigator, "mediaDevices");
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  function video() {
    const element = document.createElement("video");
    Object.defineProperty(element, "play", {
      configurable: true,
      value: vi.fn().mockResolvedValue(undefined),
    });
    Object.defineProperty(element, "pause", { configurable: true, value: vi.fn() });
    return element;
  }

  it("uses the native detector only when QR is a supported format and releases its stream", async () => {
    const { stream, stop } = cameraStream();
    const detector = { detect: vi.fn().mockResolvedValue([]) };
    function Detector() {
      return detector;
    }
    Object.assign(Detector, {
      getSupportedFormats: vi.fn().mockResolvedValue(["qr_code"]),
    });
    vi.stubGlobal("BarcodeDetector", Detector);
    vi.mocked(navigator.mediaDevices.getUserMedia).mockResolvedValue(stream);

    const session = await new WebQrScannerCapability().start(video(), undefined, vi.fn(), vi.fn());

    expect(session.engine).toBe("native");
    expect(zxing.decodeFromConstraints).not.toHaveBeenCalled();
    session.stop();
    expect(stop).toHaveBeenCalled();
  });

  it("uses ZXing when the native detector cannot decode QR", async () => {
    function Detector() {
      return undefined;
    }
    Object.assign(Detector, {
      getSupportedFormats: vi.fn().mockResolvedValue(["aztec"]),
    });
    vi.stubGlobal("BarcodeDetector", Detector);

    const session = await new WebQrScannerCapability().start(video(), undefined, vi.fn(), vi.fn());

    expect(session.engine).toBe("fallback");
    expect(zxing.decodeFromConstraints).toHaveBeenCalled();
  });

  it("stops the native stream before falling back when starting its video fails", async () => {
    const { stream, stop } = cameraStream();
    function Detector() {
      return { detect: vi.fn() };
    }
    Object.assign(Detector, {
      getSupportedFormats: vi.fn().mockResolvedValue(["qr_code"]),
    });
    vi.stubGlobal("BarcodeDetector", Detector);
    vi.mocked(navigator.mediaDevices.getUserMedia).mockResolvedValue(stream);
    const brokenVideo = video();
    Object.defineProperty(brokenVideo, "play", {
      configurable: true,
      value: vi.fn().mockRejectedValue(new Error("play failed")),
    });

    const session = await new WebQrScannerCapability().start(
      brokenVideo,
      undefined,
      vi.fn(),
      vi.fn(),
    );

    expect(stop).toHaveBeenCalled();
    expect(session.engine).toBe("fallback");
  });
});
