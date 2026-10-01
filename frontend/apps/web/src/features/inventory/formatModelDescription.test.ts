import { describe, expect, it } from "vitest";
import { formatModelDescription } from "./formatModelDescription";
describe("compact model description", () => {
  it("joins trimmed nonempty lines and preserves case and punctuation", () => {
    const source = "  CAT6 patch cables\r\n\r\n Red and blue. \n 1 metre \n";
    expect(formatModelDescription(source)).toBe("CAT6 patch cables, Red and blue. 1 metre");
    expect(source).toContain("\n");
    expect(formatModelDescription("  ")).toBe("");
    expect(formatModelDescription(null)).toBe("");
  });
});
