import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { QrScannerCapability } from "../../platform/capabilities/QrScannerCapability";
import { ScannerPage } from "./ScannerPage";

const api = vi.hoisted(() => ({
  lookupScannedAsset: vi.fn(),
  scannerErrorMessage: vi.fn((error: Error) => error.message),
}));

vi.mock("./scannerApi", () => api);

const asset = {
  id: "asset-1",
  assetModelId: "model-1",
  assetModelName: "Cable",
  publicCode: "000000",
  unitNumber: 1,
  individualName: undefined,
  displayName: "Cable 1",
  condition: "GOOD",
  lifecycleState: "ACTIVE",
  purchaseDate: undefined,
  archived: false,
  metadataIncomplete: false,
  values: [],
  createdAt: "2026-09-26T00:00:00Z",
  updatedAt: "2026-09-26T00:00:00Z",
};

function scannerCapability() {
  let codeListener: ((code: string) => void) | undefined;
  const capability: QrScannerCapability = {
    availability: () => "ready",
    listCameras: vi.fn().mockResolvedValue([]),
    start: vi.fn().mockImplementation(async (_video, _device, onCode) => {
      codeListener = onCode;
      return { engine: "native", stop: vi.fn() };
    }),
  };
  return { capability, emit: (code: string) => codeListener?.(code) };
}

function renderPage(capability: QrScannerCapability) {
  render(
    <MemoryRouter initialEntries={["/scan"]}>
      <Routes>
        <Route path="/scan" element={<ScannerPage capability={capability} />} />
        <Route path="/scan/assets/:assetId" element={<div>Scan result route</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("ScannerPage", () => {
  beforeEach(() => {
    api.lookupScannedAsset.mockReset();
    api.lookupScannedAsset.mockResolvedValue({ kind: "ok", data: asset });
  });

  it("uses the established aliases and checksum validation for permanent manual entry", async () => {
    const user = userEvent.setup();
    const { capability } = scannerCapability();
    renderPage(capability);

    await user.type(screen.getByLabelText("Public asset code"), "o0-oo 0o");
    await user.click(screen.getByRole("button", { name: "Open result" }));

    await waitFor(() => expect(api.lookupScannedAsset).toHaveBeenCalledWith("000000"));
    expect(await screen.findByText("Scan result route")).toBeInTheDocument();
  });

  it("rejects a manual checksum mismatch before lookup", async () => {
    const user = userEvent.setup();
    const { capability } = scannerCapability();
    renderPage(capability);

    await user.type(screen.getByLabelText("Public asset code"), "7K3MXZ");
    await user.click(screen.getByRole("button", { name: "Open result" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/does not check out/i);
    expect(api.lookupScannedAsset).not.toHaveBeenCalled();
  });

  it("suppresses the same continuous scan for 1.5 seconds", async () => {
    const user = userEvent.setup();
    const scanner = scannerCapability();
    renderPage(scanner.capability);
    await user.click(screen.getByRole("button", { name: "Start camera" }));
    await waitFor(() => expect(scanner.capability.start).toHaveBeenCalled());

    scanner.emit("7K3MXY");
    scanner.emit("7K3MXY");

    await waitFor(() => expect(api.lookupScannedAsset).toHaveBeenCalledTimes(1));
    expect(screen.getByRole("alert")).toHaveTextContent(/Duplicate scan ignored/);
  });

  it("does not navigate when a stale lookup resolves after the entry changes", async () => {
    const user = userEvent.setup();
    let resolveLookup: (value: unknown) => void = () => undefined;
    api.lookupScannedAsset.mockReturnValue(
      new Promise((resolve) => {
        resolveLookup = resolve;
      }),
    );
    const { capability } = scannerCapability();
    renderPage(capability);

    await user.type(screen.getByLabelText("Public asset code"), "7K3MXY");
    await user.click(screen.getByRole("button", { name: "Open result" }));
    await user.clear(screen.getByLabelText("Public asset code"));
    await user.type(screen.getByLabelText("Public asset code"), "000000");
    resolveLookup({ kind: "ok", data: asset });

    await Promise.resolve();
    await Promise.resolve();
    expect(screen.queryByText("Scan result route")).not.toBeInTheDocument();
  });

  it("invalidates an earlier camera lookup when a later camera payload is unreadable", async () => {
    const user = userEvent.setup();
    let resolveLookup: (value: unknown) => void = () => undefined;
    api.lookupScannedAsset.mockReturnValue(
      new Promise((resolve) => {
        resolveLookup = resolve;
      }),
    );
    const scanner = scannerCapability();
    renderPage(scanner.capability);
    await user.click(screen.getByRole("button", { name: "Start camera" }));
    await waitFor(() => expect(scanner.capability.start).toHaveBeenCalled());

    scanner.emit("7K3MXY");
    scanner.emit("not a Tarpeisto code");
    resolveLookup({ kind: "ok", data: asset });

    await Promise.resolve();
    await Promise.resolve();
    expect(screen.queryByText("Scan result route")).not.toBeInTheDocument();
  });
});
