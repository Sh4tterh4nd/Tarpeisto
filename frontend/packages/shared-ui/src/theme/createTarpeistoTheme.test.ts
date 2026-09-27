import { describe, expect, it } from "vitest";
import { createTarpeistoTheme } from "./createTarpeistoTheme";
import { colorTokens, touchTargetTokens } from "./tokens";

describe("createTarpeistoTheme", () => {
  it("builds a light theme using the light color tokens", () => {
    const theme = createTarpeistoTheme("light");

    expect(theme.palette.mode).toBe("light");
    expect(theme.palette.primary.main).toBe(colorTokens.light.primary);
    expect(theme.palette.background.default).toBe(colorTokens.light.background);
  });

  it("builds a dark theme using the dark color tokens", () => {
    const theme = createTarpeistoTheme("dark");

    expect(theme.palette.mode).toBe("dark");
    expect(theme.palette.primary.main).toBe(colorTokens.dark.primary);
    expect(theme.palette.background.default).toBe(colorTokens.dark.background);
  });

  it("defaults to light mode when no mode is given", () => {
    const theme = createTarpeistoTheme();

    expect(theme.palette.mode).toBe("light");
  });

  it("enforces the minimum mobile touch-target size on button-like controls", () => {
    const theme = createTarpeistoTheme("light");
    const buttonBaseRoot = theme.components?.MuiButtonBase?.styleOverrides?.root as
      Record<string, unknown> | undefined;

    expect(buttonBaseRoot?.["minHeight"]).toBe(touchTargetTokens.minSize);
    expect(buttonBaseRoot?.["minWidth"]).toBe(touchTargetTokens.minSize);
  });
});
