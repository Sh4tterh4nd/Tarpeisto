/** The PDF and preview share the exact strict 65% blackness threshold. */
export function packingHeaderTextColor(color: string): "#FFFFFF" | "#000000" {
  const red = Number.parseInt(color.slice(1, 3), 16),
    green = Number.parseInt(color.slice(3, 5), 16),
    blue = Number.parseInt(color.slice(5, 7), 16);
  return 299 * red + 587 * green + 114 * blue < 89250 ? "#FFFFFF" : "#000000";
}
export function normalizedPackingColor(raw: string): string | undefined {
  const trimmed = raw.trim();
  const color = (trimmed.startsWith("#") ? trimmed : "#" + trimmed).toUpperCase();
  return /^#[0-9A-F]{6}$/.test(color) ? color : undefined;
}
