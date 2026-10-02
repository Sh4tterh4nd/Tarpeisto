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
    const { capability } = cameraCapability(engine);
    render(<ScannerViewport capability={capability} onCode={vi.fn()} />);

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(capability.start).toHaveBeenCalledTimes(1);
  });

  it("releases the previous session when switching cameras and when unmounted", async () => {
    const user = userEvent.setup();
    const { capability, stop } = cameraCapability();
    const { unmount } = render(<ScannerViewport capability={capability} onCode={vi.fn()} />);

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

    await screen.findByRole("button", { name: "Pause camera" });
    await user.click(screen.getByRole("button", { name: "Pause camera" }));

    await waitFor(() => expect(stop).toHaveBeenCalledTimes(1));
    expect(screen.getByRole("button", { name: "Start camera" })).toBeInTheDocument();
  });

  it("rejects callbacks from a paused default session and accepts its fresh resumed session", async () => {
    const user = userEvent.setup();
    const { capability, stop } = cameraCapability();
    const onCode = vi.fn();
    render(<ScannerViewport capability={capability} onCode={onCode} />);

    await screen.findByRole("button", { name: "Pause camera" });
    const previousCallback = vi.mocked(capability.start).mock.calls[0]![2];
    previousCallback("123452");
    expect(onCode).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole("button", { name: "Pause camera" }));
    expect(stop).toHaveBeenCalledTimes(1);
    previousCallback("123452");
    expect(onCode).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole("button", { name: "Start camera" }));
    await screen.findByRole("button", { name: "Pause camera" });
    expect(capability.start).toHaveBeenCalledTimes(2);
    previousCallback("123452");
    expect(onCode).toHaveBeenCalledTimes(1);
    vi.mocked(capability.start).mock.calls[1]![2]("123452");
    expect(onCode).toHaveBeenCalledTimes(2);
  });

  it("reports an explicit camera-permission state", async () => {
    const capability: QrScannerCapability = {
      availability: () => "ready",
      listCameras: vi.fn(),
      start: vi.fn().mockRejectedValue(new DOMException("Denied", "NotAllowedError")),
    };
    render(<ScannerViewport capability={capability} onCode={vi.fn()} />);

    expect(await screen.findByRole("alert")).toHaveTextContent(/Camera permission was denied/);
    expect(capability.start).toHaveBeenCalledTimes(1);
  });

  it("keeps the camera session running when the parent changes its scan callback", async () => {
    const { capability, stop } = cameraCapability();
    const first = vi.fn();
    const second = vi.fn();
    const { rerender } = render(<ScannerViewport capability={capability} onCode={first} />);
    await screen.findByRole("button", { name: "Pause camera" });
    rerender(<ScannerViewport capability={capability} onCode={second} />);
    expect(capability.start).toHaveBeenCalledTimes(1);
    expect(stop).not.toHaveBeenCalled();
    const callback = vi.mocked(capability.start).mock.calls[0]![2];
    callback("123452");
    expect(second).toHaveBeenCalledWith("123452");
    expect(first).not.toHaveBeenCalled();
  });

  it("compact mode pauses tracks for dialogs and rejects stale camera callbacks", async () => {
    const user = userEvent.setup();
    const { capability, stop } = cameraCapability();
    const onCode = vi.fn(),
      onManual = vi.fn();
    const rendered = render(
      <ScannerViewport compact capability={capability} onCode={onCode} onManual={onManual} />,
    );
    await waitFor(() => expect(capability.start).toHaveBeenCalledTimes(1));
    const callback = vi.mocked(capability.start).mock.calls[0]![2];
    rendered.rerender(
      <ScannerViewport
        compact
        paused
        capability={capability}
        onCode={onCode}
        onManual={onManual}
      />,
    );
    callback("123452");
    expect(onCode).not.toHaveBeenCalled();
    await waitFor(() => expect(stop).toHaveBeenCalledTimes(1));
    rendered.rerender(
      <ScannerViewport compact capability={capability} onCode={onCode} onManual={onManual} />,
    );
    await waitFor(() => expect(capability.start).toHaveBeenCalledTimes(2));
    await user.click(screen.getByRole("button", { name: "Scanner settings" }));
    vi.mocked(capability.start).mock.calls[1]![2]("123452");
    expect(onCode).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Enter code manually" }));
    expect(onManual).toHaveBeenCalledTimes(1);
  });
  it("compact denied-camera fallback remains visible with a manual action", async () => {
    const capability: QrScannerCapability = {
      availability: () => "ready",
      listCameras: vi.fn(),
      start: vi.fn().mockRejectedValue(new DOMException("Denied", "NotAllowedError")),
    };
    const user = userEvent.setup(),
      onManual = vi.fn();
    render(
      <ScannerViewport compact capability={capability} onCode={vi.fn()} onManual={onManual} />,
    );
    expect(await screen.findByRole("status")).toHaveTextContent(/Camera permission was denied/);
    await user.click(screen.getByRole("button", { name: "Scanner settings" }));
    await user.click(screen.getByRole("button", { name: "Enter code manually" }));
    expect(onManual).toHaveBeenCalledTimes(1);
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
