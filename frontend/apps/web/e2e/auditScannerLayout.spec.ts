import { expect, test } from "@playwright/test";
import { enterAuditCode, finishAudit } from "./auditActions";
const task = "11111111-1111-1111-1111-111111111111",
  auditId = "66666666-6666-6666-6666-666666666666";
for (const size of [
  { width: 1365, height: 768, name: "desktop" },
  { width: 393, height: 851, name: "phone" },
  { width: 568, height: 320, name: "short" },
]) {
  test(`camera audit fits ${size.name}, shows scoped details and finishes after closing rescan`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize(size);
    await page.addInitScript(() =>
      Object.defineProperty(navigator, "mediaDevices", {
        configurable: true,
        value: {
          getUserMedia: async () => {
            throw new DOMException("Denied by fixture", "NotAllowedError");
          },
          enumerateDevices: async () => [],
        },
      }),
    );
    const initial = {
      id: auditId,
      taskId: task,
      batchId: "22222222-2222-2222-2222-222222222222",
      containerAssetId: "33333333-3333-3333-3333-333333333333",
      state: "IN_PROGRESS",
      blockingReasons: [
        "A containing container has a pending audit: " + "Outer equipment handoff case ".repeat(12),
      ],
      scans: [],
      findings: [],
      expectedRequirements: [
        {
          id: "required-model",
          type: "MODEL_QUANTITY",
          assetModelId: "cables",
          requiredQuantity: 20,
          matchedQuantity: 13,
          satisfied: false,
          snapshot: JSON.stringify({ modelName: "LAN cable 20m" }),
        },
        {
          id: "missing-exact",
          type: "SPECIFIC_ASSET",
          specificAssetId: "missing-unit",
          matchedQuantity: 0,
          satisfied: false,
          snapshot: JSON.stringify({ assetName: "Required adapter", assetCode: "5J3H0F" }),
        },
      ],
    };
    let completed = false;
    await page.route("**/api/v1/**", async (route) => {
      const request = route.request(),
        path = new URL(request.url()).pathname;
      if (path === "/api/v1/session")
        return route.fulfill({
          json: {
            userId: "44444444-4444-4444-4444-444444444444",
            username: "operator",
            displayName: "Audit operator",
            organizationId: "55555555-5555-5555-5555-555555555555",
            role: "OPERATOR_AUDITOR",
          },
        });
      if (path === "/api/v1/application")
        return route.fulfill({
          json: { applicationName: "Tarpeisto", version: "test", oidcConfigured: false },
        });
      if (path.endsWith(`/tasks/${task}/container`))
        return route.fulfill({
          json: {
            id: initial.containerAssetId,
            displayName:
              "Mixed cable case with a very long inventory description and equipment identifiers",
            publicCode: "000000",
            sealable: true,
          },
        });
      if (path.endsWith(`/tasks/${task}`)) return route.fulfill({ json: initial });
      if (path.endsWith("/manual-candidates"))
        return route.fulfill({
          json: {
            items: [
              {
                id: "known-unit",
                displayName: "LAN cable 14",
                publicCode: "ABC123",
                assetModelId: "cables",
                modelName: "LAN cable 20m",
                active: true,
              },
            ],
          },
        });
      if (path.endsWith("/evidence")) return route.fulfill({ json: [] });
      if (path.endsWith("/complete")) {
        const body = request.postDataJSON();
        expect(body.containerCode).toBe("000000");
        expect(body.confirmMissing).toBe(true);
        expect(body.sealConfirmed).toBe(true);
        completed = true;
        return route.fulfill({ json: { ...initial, state: "COMPLETED" } });
      }
      return route.fulfill({ status: 404, json: { title: "Not found", status: 404 } });
    });
    await page.goto(`/audits/tasks/${task}`);
    const gear = page.getByRole("button", { name: "Scanner settings" });
    await expect(gear).toBeVisible();
    await expect(page.getByText(/Camera permission was denied/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Report", exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Finish", exact: true })).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollHeight <= innerHeight + 1),
    ).toBe(true);
    const box = await gear.boundingBox(),
      camera = await page.getByRole("region", { name: "Audit camera" }).boundingBox();
    expect(box!.width).toBeGreaterThanOrEqual(44);
    expect(box!.height).toBeGreaterThanOrEqual(44);
    expect(box!.y + box!.height).toBeLessThanOrEqual(camera!.y + camera!.height + 1);
    await page.screenshot({
      animations: "disabled",
      path: testInfo.outputPath(`audit-${size.name}-main.png`),
    });
    await page.getByRole("button", { name: "Details", exact: true }).click();
    await expect(page.getByText("13/20 found")).toBeVisible();
    await page.getByRole("button", { name: /LAN cable 20m 13\/20 found/ }).click();
    await expect(page.getByText(/LAN cable 14 - ABC123/, { exact: false }).first()).toBeVisible();
    await page.screenshot({
      animations: "disabled",
      path: testInfo.outputPath(`audit-${size.name}-details.png`),
    });
    await page.getByRole("button", { name: "Back to camera" }).click();
    await enterAuditCode(page, "000000");
    await expect(page.getByText("Required adapter - 5J3H0F - 1 remaining")).toBeVisible();
    await expect(page.getByText("LAN cable 20m - 7 remaining")).toBeVisible();
    await page.screenshot({
      animations: "disabled",
      path: testInfo.outputPath(`audit-${size.name}-finish.png`),
    });
    await page.getByRole("button", { name: "Continue auditing" }).click();
    await finishAudit(page, "000000");
    await expect(page.getByText("Audit completed", { exact: true })).toBeVisible();
    expect(completed).toBe(true);
  });
}
