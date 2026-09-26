/**
 * BigContainers design tokens.
 *
 * These are the raw values behind `createBigContainersTheme`. Feature code should
 * consume the MUI theme (colors, spacing, typography) rather than importing these
 * tokens directly, so the theme factory remains the single place that assembles them.
 */

export const colorTokens = {
  light: {
    primary: "#0F5A46",
    primaryContrast: "#FFFFFF",
    secondary: "#B25E09",
    secondaryContrast: "#FFFFFF",
    background: "#F7F7F5",
    surface: "#FFFFFF",
    textPrimary: "#1B1B18",
    textSecondary: "#4B4B45",
    error: "#B3261E",
    warning: "#8A5300",
    success: "#146C43",
    outline: "#C9C9C0",
  },
  dark: {
    primary: "#6FD8B8",
    primaryContrast: "#00231A",
    secondary: "#F2B366",
    secondaryContrast: "#3A2100",
    background: "#121412",
    surface: "#1B1E1B",
    textPrimary: "#F1F1EC",
    textSecondary: "#C4C4BC",
    error: "#FFB4AB",
    warning: "#FFCB7A",
    success: "#7FDDA0",
    outline: "#46483F",
  },
} as const;

export type ColorMode = keyof typeof colorTokens;

/** 4px base spacing scale, expressed in CSS pixels. */
export const spacingTokens = {
  unit: 4,
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
  xxl: 48,
} as const;

export const typographyTokens = {
  fontFamily: '"Inter", "Roboto", "Helvetica", "Arial", sans-serif',
  scale: {
    display: { fontSize: "2.25rem", lineHeight: 1.2, fontWeight: 700 },
    headline: { fontSize: "1.75rem", lineHeight: 1.25, fontWeight: 700 },
    title: { fontSize: "1.25rem", lineHeight: 1.3, fontWeight: 600 },
    body: { fontSize: "1rem", lineHeight: 1.5, fontWeight: 400 },
    label: { fontSize: "0.875rem", lineHeight: 1.4, fontWeight: 500 },
    caption: { fontSize: "0.75rem", lineHeight: 1.4, fontWeight: 400 },
  },
} as const;

/**
 * Minimum interactive target size in CSS pixels (WCAG 2.2 AA, policy 6.3).
 * Every tappable control's rendered box should be at least this large.
 */
export const touchTargetTokens = {
  minSize: 44,
} as const;

export const shapeTokens = {
  borderRadius: 8,
} as const;
