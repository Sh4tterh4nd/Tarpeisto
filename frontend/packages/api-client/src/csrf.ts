/**
 * Spring Security's `CookieCsrfTokenRepository` (ADR-0001) publishes the CSRF
 * token as a readable cookie so the browser can echo it back as a request
 * header on mutating requests. Reading it is not a security boundary by
 * itself; it only works because the session cookie is `HttpOnly`,
 * same-origin, and `SameSite=Lax`.
 */
const CSRF_COOKIE_NAME = "XSRF-TOKEN";
export const CSRF_HEADER_NAME = "X-XSRF-TOKEN";

const MUTATING_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

export function isMutatingMethod(method: string): boolean {
  return MUTATING_METHODS.has(method.toUpperCase());
}

export function readCsrfCookie(cookieString: string = getDocumentCookie()): string | undefined {
  for (const part of cookieString.split(";")) {
    const separatorIndex = part.indexOf("=");
    if (separatorIndex === -1) {
      continue;
    }
    const name = part.slice(0, separatorIndex).trim();
    if (name === CSRF_COOKIE_NAME) {
      return decodeURIComponent(part.slice(separatorIndex + 1).trim());
    }
  }
  return undefined;
}

function getDocumentCookie(): string {
  return typeof document === "undefined" ? "" : document.cookie;
}
