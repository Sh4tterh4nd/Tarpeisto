import { afterEach, describe, expect, it, vi } from "vitest";
describe("invitation fragment capture", () => {
  afterEach(() => {
    window.history.replaceState(null, "", "/");
    vi.resetModules();
  });
  it("immediately removes the secret from address history and holds it only in memory", async () => {
    window.history.replaceState(null, "", "/join#token=secret-example");
    const capture = await import("./invitationToken");
    expect(window.location.hash).toBe("");
    expect(window.location.pathname).toBe("/join");
    expect(capture.readInvitationToken()).toBe("secret-example");
    expect(localStorage.getItem("token")).toBeNull();
    capture.clearInvitationToken();
    expect(capture.readInvitationToken()).toBeUndefined();
  });
});
