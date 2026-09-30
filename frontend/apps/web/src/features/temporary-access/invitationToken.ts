// Capture into memory before session restoration. Never write invitation credentials to storage.
let invitationToken: string | undefined;
if (window.location.pathname === "/join") {
  invitationToken = new URLSearchParams(window.location.hash.slice(1)).get("token") ?? undefined;
  window.history.replaceState(window.history.state, "", window.location.pathname);
}
export const readInvitationToken = () => invitationToken;
export function clearInvitationToken() {
  invitationToken = undefined;
}
