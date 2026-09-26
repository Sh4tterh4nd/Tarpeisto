import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type {
  QrScannerCapability,
  QrScannerEngine,
} from "../../platform/capabilities/QrScannerCapability";
import { ScannerViewport } from "./ScannerViewport";

function cameraCapability(engine: QrScannerEngine = "native") {
  const stop = vi.fn();
  const capability: QrScannerCapability = {
    availability: () => "ready",
    listCameras: vi.fn().mockResolvedValue([
      { deviceId: "rear", label: "Rear camera" },
      { deviceId: "front", label: "Front camera" },
    ]),
    start: vi.fn().mockResolvedValue({ engine, stop }),
  };
  return { capability, stop };
}

describe("ScannerViewport", () => {
  it.each([
    ["native", /Fast browser decoder active/],
    ["fallback", /Portable QR decoder active/],
  ] as const)("shows the %s decoder mode", async (engine, message) => {
    const user = userEvent.setup();
    const { capability } = cameraCapability(engine);
    render(<ScannerViewport capability={capability} onCode={vi.fn()} />);

    await user.click(screen.getByRole("button", { name: "Start camera" }));

    expect(await screen.findByText(message)).toBeInTheDocument();
  });

  it("releases the previous session when switching cameras and when unmounted", async () => {
    const user = userEvent.setup();
    const { capability, stop } = cameraCapability();
    const { unmount } = render(<ScannerViewport capability={capability} onCode={vi.fn()} />);

    await user.click(screen.getByRole("button", { name: "Start camera" }));
    await screen.findByLabelText("Camera");
    await user.click(screen.getByRole("combobox", { name: "Camera" }));
    await user.click(await screen.findByRole("option", { name: "Front camera" }));

    await waitFor(() => expect(stop).toHaveBeenCalledTimes(1));
    unmount();
    expect(stop).toHaveBeenCalledTimes(2);
  });

  it("stops the camera on pause and offers a resume action", async () => {
    const user = userEvent.setup();
    const { capability, stop } = cameraCapability();
    render(<ScannerViewport capability={capability} onCode={vi.fn()} />);

    await user.click(screen.getByRole("button", { name: "Start camera" }));
    await screen.findByRole("button", { name: "Pause camera" });
    await user.click(screen.getByRole("button", { name: "Pause camera" }));

    await waitFor(() => expect(stop).toHaveBeenCalledTimes(1));
    expect(screen.getByRole("button", { name: "Start camera" })).toBeInTheDocument();
  });

  it("reports an explicit camera-permission state", async () => {
    const user = userEvent.setup();
    const capability: QrScannerCapability = {
      availability: () => "ready",
      listCameras: vi.fn(),
      start: vi.fn().mockRejectedValue(new DOMException("Denied", "NotAllowedError")),
    };
    render(<ScannerViewport capability={capability} onCode={vi.fn()} />);

    await user.click(screen.getByRole("button", { name: "Start camera" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/Camera permission was denied/);
  });

  it("surfaces unsupported and insecure contexts without offering camera start", () => {
    const unsupported: QrScannerCapability = {
      availability: () => "unsupported",
      listCameras: vi.fn(),
      start: vi.fn(),
    };
    const { unmount } = render(<ScannerViewport capability={unsupported} onCode={vi.fn()} />);
    expect(screen.getByRole("alert")).toHaveTextContent(/does not expose camera scanning/);
    expect(screen.getByRole("button", { name: "Start camera" })).toBeDisabled();

    const insecure: QrScannerCapability = { ...unsupported, availability: () => "insecure" };
    unmount();
    render(<ScannerViewport capability={insecure} onCode={vi.fn()} />);
    expect(screen.getByRole("alert")).toHaveTextContent(/needs a secure HTTPS connection/);
  });
});
