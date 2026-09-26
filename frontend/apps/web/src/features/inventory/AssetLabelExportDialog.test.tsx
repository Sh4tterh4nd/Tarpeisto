import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AssetLabelExportDialog } from "./AssetLabelExportDialog";

const api = vi.hoisted(() => ({
  createAssetLabelCalibration: vi.fn(),
  createAssetLabelPdf: vi.fn(),
  createPtouchCsv: vi.fn(),
  downloadAssetLabelFile: vi.fn(),
}));

vi.mock("./assetLabelApi", () => api);
vi.mock("./inventoryApi", () => ({ errorMessage: (error: Error) => error.message }));

const labelPdf = new Blob(["label-pdf"], { type: "application/pdf" });
const calibrationPdf = new Blob(["calibration-pdf"], { type: "application/pdf" });
const ptouchCsv = new Blob(["model_name\r\n"], { type: "text/csv" });

describe("AssetLabelExportDialog", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.createAssetLabelPdf.mockResolvedValue({ kind: "ok", data: labelPdf });
    api.createAssetLabelCalibration.mockResolvedValue({ kind: "ok", data: calibrationPdf });
    api.createPtouchCsv.mockResolvedValue({ kind: "ok", data: ptouchCsv });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("validates geometry and sends the selected IDs with a calibrated labels PDF request", async () => {
    const user = userEvent.setup();
    render(<AssetLabelExportDialog assetIds={["asset-1", "asset-2"]} onClose={() => {}} />);

    expect(screen.getByText(/2 units are selected/)).toBeInTheDocument();
    expect(
      screen.getByText("Derived gutters: horizontal 0 mm; vertical 0 mm."),
    ).toBeInTheDocument();

    await user.clear(screen.getByLabelText("Skip first positions"));
    await user.type(screen.getByLabelText("Skip first positions"), "2");
    await user.click(screen.getByRole("button", { name: "Labels PDF" }));

    await waitFor(() =>
      expect(api.createAssetLabelPdf).toHaveBeenCalledWith({
        assetIds: ["asset-1", "asset-2"],
        format: "A4_70X36_24",
        skipFirstPositions: 2,
        calibration: {
          marginLeftMm: 0,
          marginTopMm: 4.5,
          horizontalPitchMm: 70,
          verticalPitchMm: 36,
        },
      }),
    );
    expect(api.downloadAssetLabelFile).toHaveBeenCalledWith(labelPdf, "asset-labels-70x36.pdf");

    await user.clear(screen.getByLabelText("Horizontal pitch"));
    await user.type(screen.getByLabelText("Horizontal pitch"), "69");
    expect(
      screen.getByText("Pitch must be at least the label size so labels do not overlap."),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Labels PDF" })).toBeDisabled();
  });

  it("downloads calibration and P-touch files through the corresponding generated operations", async () => {
    const user = userEvent.setup();
    render(<AssetLabelExportDialog assetIds={["asset-1"]} onClose={() => {}} />);

    await user.click(screen.getByRole("button", { name: "Calibration PDF" }));
    await waitFor(() =>
      expect(api.createAssetLabelCalibration).toHaveBeenCalledWith({
        format: "A4_70X36_24",
        calibration: {
          marginLeftMm: 0,
          marginTopMm: 4.5,
          horizontalPitchMm: 70,
          verticalPitchMm: 36,
        },
      }),
    );
    expect(api.downloadAssetLabelFile).toHaveBeenCalledWith(
      calibrationPdf,
      "asset-label-calibration-70x36.pdf",
    );

    await user.click(screen.getByRole("button", { name: "P-touch CSV" }));
    await waitFor(() =>
      expect(api.createPtouchCsv).toHaveBeenCalledWith({ assetIds: ["asset-1"] }),
    );
    expect(api.downloadAssetLabelFile).toHaveBeenCalledWith(ptouchCsv, "asset-labels-ptouch.csv");
  });
});
