import type { Role } from "./sessionApi";

/** The permanent roles from spec 4.1, in a stable display order. */
export const ROLE_OPTIONS: Role[] = ["OWNER", "DEPUTY", "OPERATOR_AUDITOR", "VIEWER"];

export const ROLE_LABELS: Record<Role, string> = {
  OWNER: "Owner",
  DEPUTY: "Deputy",
  OPERATOR_AUDITOR: "Operator/Auditor",
  VIEWER: "Viewer",
};
