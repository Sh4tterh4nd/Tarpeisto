import type { AuditExpectedRequirement, AuditScan } from "./auditApi";
export function expectedLabel(row: AuditExpectedRequirement) {
  try {
    const s = JSON.parse(row.snapshot) as Record<string, unknown>;
    return (
      [s["assetName"] ?? s["modelName"], s["assetCode"]].filter(Boolean).join(" - ") ||
      row.type.replaceAll("_", " ")
    );
  } catch {
    return row.type.replaceAll("_", " ");
  }
}
export function scanLabel(scan: AuditScan) {
  try {
    const s = JSON.parse(scan.contextSnapshot) as { assetName?: string; modelName?: string };
    return [s.assetName ?? s.modelName, scan.assetCode].filter(Boolean).join(" - ");
  } catch {
    return scan.assetCode;
  }
}
export function missingLabel(row: AuditExpectedRequirement) {
  const remaining =
    row.matchedQuantity === undefined
      ? undefined
      : Math.max(0, (row.requiredQuantity ?? 1) - row.matchedQuantity);
  return `${expectedLabel(row)}${remaining === undefined ? " - still expected" : ` - ${remaining} remaining`}`;
}
