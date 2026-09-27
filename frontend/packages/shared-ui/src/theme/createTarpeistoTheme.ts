import { createTheme, type Theme } from "@mui/material/styles";
import {
  type ColorMode,
  colorTokens,
  shapeTokens,
  spacingTokens,
  touchTargetTokens,
  typographyTokens,
} from "./tokens";

/**
 * Builds the Tarpeisto MUI theme for the requested color mode from the
 * project's design tokens. This is the only place a MUI theme should be
 * constructed; feature code and other `shared-ui` components consume the
 * resulting theme rather than reading tokens directly.
 */
export function createTarpeistoTheme(mode: ColorMode = "light"): Theme {
  const colors = colorTokens[mode];

  return createTheme({
    cssVariables: { colorSchemeSelector: "class" },
    palette: {
      mode,
      primary: { main: colors.primary, contrastText: colors.primaryContrast },
      secondary: { main: colors.secondary, contrastText: colors.secondaryContrast },
      background: { default: colors.background, paper: colors.surface },
      text: { primary: colors.textPrimary, secondary: colors.textSecondary },
      error: { main: colors.error },
      warning: { main: colors.warning },
      success: { main: colors.success },
      divider: colors.outline,
    },
    spacing: spacingTokens.unit,
    shape: { borderRadius: shapeTokens.borderRadius },
    typography: {
      fontFamily: typographyTokens.fontFamily,
      h1: typographyTokens.scale.display,
      h2: typographyTokens.scale.headline,
      h3: typographyTokens.scale.title,
      body1: typographyTokens.scale.body,
      body2: typographyTokens.scale.label,
      caption: typographyTokens.scale.caption,
    },
    components: {
      MuiButtonBase: {
        defaultProps: { disableRipple: false },
        styleOverrides: {
          root: {
            minHeight: touchTargetTokens.minSize,
            minWidth: touchTargetTokens.minSize,
          },
        },
      },
      MuiIconButton: {
        styleOverrides: {
          root: {
            minHeight: touchTargetTokens.minSize,
            minWidth: touchTargetTokens.minSize,
          },
        },
      },
    },
  });
}
