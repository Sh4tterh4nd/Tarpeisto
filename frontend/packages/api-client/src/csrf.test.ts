import { describe, expect, it } from "vitest";
import { isMutatingMethod, readCsrfCookie } from "./csrf";

describe("readCsrfCookie", () => {
  it("returns undefined when the cookie is absent", () => {
    expect(readCsrfCookie("")).toBeUndefined();
    expect(readCsrfCookie("other=value")).toBeUndefined();
  });

  it("reads the XSRF-TOKEN cookie among others", () => {
    expect(readCsrfCookie("a=1; XSRF-TOKEN=abc123; b=2")).toBe("abc123");
  });

  it("decodes a URI-encoded cookie value", () => {
    expect(readCsrfCookie("XSRF-TOKEN=abc%2F123")).toBe("abc/123");
  });

  it("trims surrounding whitespace around cookie pairs", () => {
    expect(readCsrfCookie("  XSRF-TOKEN = abc123 ; other=1")).toBe("abc123");
  });
});

describe("isMutatingMethod", () => {
  it("treats POST, PUT, PATCH, and DELETE as mutating", () => {
    for (const method of ["POST", "PUT", "PATCH", "DELETE", "post", "put"]) {
      expect(isMutatingMethod(method)).toBe(true);
    }
  });

  it("treats GET, HEAD, and OPTIONS as non-mutating", () => {
    for (const method of ["GET", "HEAD", "OPTIONS", "get"]) {
      expect(isMutatingMethod(method)).toBe(false);
    }
  });
});
