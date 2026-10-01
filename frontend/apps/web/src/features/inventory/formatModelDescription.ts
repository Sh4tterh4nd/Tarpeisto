/** Flatten display lines while preserving the stored description and its capitalization. */
export function formatModelDescription(description?: string | null): string {
  return (description ?? "")
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .reduce((text, line) => (text ? `${text}${text.endsWith(".") ? " " : ", "}${line}` : line), "");
}
