import { describe, expect, it } from "vitest";
import { createBigContainersTheme } from "./createBigContainersTheme";
import { colorTokens, touchTargetTokens } from "./tokens";

describe("createBigContainersTheme", () => {
  it("builds a light theme using the light color tokens", () => {
    const theme = createBigContainersTheme("light");

    expect(theme.palette.mode).toBe("light");
    expect(theme.palette.primary.main).toBe(colorTokens.light.primary);
    expect(theme.palette.background.default).toBe(colorTokens.light.background);
  });

  it("builds a dark theme using the dark color tokens", () => {
    const theme = createBigContainersTheme("dark");

    expect(theme.palette.mode).toBe("dark");
    expect(theme.palette.primary.main).toBe(colorTokens.dark.primary);
    expect(theme.palette.background.default).toBe(colorTokens.dark.background);
  });

  it("defaults to light mode when no mode is given", () => {
    const theme = createBigContainersTheme();

    expect(theme.palette.mode).toBe("light");
  });

  it("enforces the minimum mobile touch-target size on button-like controls", () => {
    const theme = createBigContainersTheme("light");
    const buttonBaseRoot = theme.components?.MuiButtonBase?.styleOverrides?.root as
      Record<string, unknown> | undefined;

    expect(buttonBaseRoot?.["minHeight"]).toBe(touchTargetTokens.minSize);
    expect(buttonBaseRoot?.["minWidth"]).toBe(touchTargetTokens.minSize);
  });
});
