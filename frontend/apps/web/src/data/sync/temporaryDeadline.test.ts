import { describe, expect, it } from "vitest";
import { temporaryAccessExpired } from "./temporaryDeadline";
import type { SessionPrincipal } from "../../features/identity/sessionApi";
const principal: SessionPrincipal = {
  userId: "volunteer",
  username: "volunteer",
  displayName: "Volunteer",
  organizationId: "org",
  role: "OPERATOR_AUDITOR",
};
describe("temporary access deadline", () => {
  it("leaves permanent sessions to the authoritative server", () =>
    expect(temporaryAccessExpired(principal, Number.MAX_SAFE_INTEGER)).toBe(false));
  it("expires at issuance deadline including offline cached metadata", () => {
    const volunteer = {
      ...principal,
      temporaryAccess: {
        sessionId: "session",
        invitationId: "invitation",
        expiresAt: "2026-09-30T12:00:00Z",
      },
    };
    const deadline = Date.parse(volunteer.temporaryAccess.expiresAt);
    expect(temporaryAccessExpired(volunteer, deadline - 1)).toBe(false);
    expect(temporaryAccessExpired(volunteer, deadline)).toBe(true);
    expect(
      temporaryAccessExpired(
        { ...volunteer, temporaryAccess: { ...volunteer.temporaryAccess, expiresAt: "invalid" } },
        deadline,
      ),
    ).toBe(true);
  });
});
