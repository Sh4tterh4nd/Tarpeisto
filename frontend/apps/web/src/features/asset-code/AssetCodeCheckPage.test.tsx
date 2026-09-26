import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { AssetCodeCheckPage } from "./AssetCodeCheckPage";

describe("AssetCodeCheckPage", () => {
  it("confirms a valid code", async () => {
    const user = userEvent.setup();
    render(<AssetCodeCheckPage />);

    await user.type(screen.getByLabelText("Public asset code"), "7k3-mxy");
    await user.click(screen.getByRole("button", { name: "Validate code" }));

    expect(await screen.findByRole("status")).toHaveTextContent("7K3MXY checks out.");
  });

  it("reports a checksum mismatch as a transcription error and moves focus to it", async () => {
    const user = userEvent.setup();
    render(<AssetCodeCheckPage />);

    await user.type(screen.getByLabelText("Public asset code"), "7K3MXZ");
    await user.click(screen.getByRole("button", { name: "Validate code" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/transcription error/i);
    expect(alert).toHaveFocus();
  });

  it("reports an empty submission", async () => {
    const user = userEvent.setup();
    render(<AssetCodeCheckPage />);

    await user.click(screen.getByRole("button", { name: "Validate code" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Enter a public asset code.");
  });
});
