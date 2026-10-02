import { expect, type Page } from "@playwright/test";
export async function enterAuditCode(page: Page, code: string, start = false) {
  await page.getByRole("button", { name: "Scanner settings" }).click();
  await page.getByRole("button", { name: "Enter code manually" }).click();
  await page
    .getByRole("textbox", {
      name: start ? "Scan assigned container to start" : "Scan or enter item code",
    })
    .fill(code);
  await page
    .getByRole("textbox", {
      name: start ? "Scan assigned container to start" : "Scan or enter item code",
    })
    .press("Enter");
}
export async function completionReady(page: Page, ready: boolean) {
  await page.getByRole("button", { name: "Finish", exact: true }).click();
  if (ready)
    await expect(page.getByRole("button", { name: "Scan closed container" })).toBeEnabled();
  else await expect(page.getByRole("button", { name: "Scan closed container" })).toBeDisabled();
  await page.getByRole("button", { name: "Continue auditing" }).click();
  await expect(page.getByRole("dialog")).toBeHidden();
}
export async function finishAudit(page: Page, code: string) {
  await page.getByRole("button", { name: "Finish", exact: true }).click();
  const missing = page.getByRole("checkbox", { name: /remaining expected/ });
  if (await missing.isVisible()) await missing.check();
  await page.getByRole("button", { name: "Scan closed container" }).click();
  await expect(page.getByRole("dialog")).toBeHidden();
  await enterAuditCode(page, code);
  const seal = page.getByRole("checkbox", { name: /required seal/ });
  if (await seal.isVisible()) await seal.check();
  await page.getByRole("button", { name: "Complete audit" }).click();
}
