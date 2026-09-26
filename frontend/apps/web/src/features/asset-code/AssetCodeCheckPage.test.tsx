import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AssetCodeCheckPage } from "./AssetCodeCheckPage";

describe("AssetCodeCheckPage", () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          id: "11111111-1111-1111-1111-111111111111",
          assetModelId: "22222222-2222-2222-2222-222222222222",
          assetModelName: "UniFi AP-HD",
          publicCode: "7K3MXY",
          unitNumber: 3,
          individualName: null,
          displayName: "UniFi AP-HD 3",
          condition: "GOOD",
          lifecycleState: "ACTIVE",
          purchaseDate: null,
          archived: false,
          metadataIncomplete: false,
          values: [],
          createdAt: "2026-09-26T10:00:00Z",
          updatedAt: "2026-09-26T10:00:00Z",
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      ),
    );
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  function renderPage() {
    render(
      <MemoryRouter>
        <AssetCodeCheckPage />
      </MemoryRouter>,
    );
  }

  it("normalizes a valid code and opens its inventory result", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText("Public asset code"), "7k3-mxy");
    await user.click(screen.getByRole("button", { name: "Find asset" }));

    expect(await screen.findByRole("heading", { name: "UniFi AP-HD 3" })).toBeInTheDocument();
    expect(screen.getByText("7K3MXY")).toBeInTheDocument();
    const request = vi.mocked(global.fetch).mock.calls[0]?.[0];
    const requestUrl = request instanceof Request ? request.url : String(request);
    expect(requestUrl).toContain("/api/v1/assets/by-code/7K3MXY");
  });

  it("reports a checksum mismatch as a transcription error and moves focus to it", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText("Public asset code"), "7K3MXZ");
    await user.click(screen.getByRole("button", { name: "Find asset" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/transcription error/i);
    expect(alert).toHaveFocus();
  });

  it("reports an empty submission", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole("button", { name: "Find asset" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Enter a public asset code.");
  });

  it("ignores a lookup that completes after the input changes", async () => {
    const user = userEvent.setup();
    let resolveLookup: (response: Response) => void = () => undefined;
    global.fetch = vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          resolveLookup = resolve;
        }),
    );
    renderPage();

    await user.type(screen.getByLabelText("Public asset code"), "7K3MXY");
    await user.click(screen.getByRole("button", { name: "Find asset" }));
    await user.clear(screen.getByLabelText("Public asset code"));
    await user.type(screen.getByLabelText("Public asset code"), "000000");

    resolveLookup(
      new Response(
        JSON.stringify({
          id: "11111111-1111-1111-1111-111111111111",
          assetModelId: "22222222-2222-2222-2222-222222222222",
          assetModelName: "UniFi AP-HD",
          publicCode: "7K3MXY",
          unitNumber: 3,
          displayName: "UniFi AP-HD 3",
          condition: "GOOD",
          lifecycleState: "ACTIVE",
          archived: false,
          metadataIncomplete: false,
          values: [],
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      ),
    );
    await Promise.resolve();
    await Promise.resolve();

    expect(screen.queryByRole("heading", { name: "UniFi AP-HD 3" })).not.toBeInTheDocument();
  });
});
