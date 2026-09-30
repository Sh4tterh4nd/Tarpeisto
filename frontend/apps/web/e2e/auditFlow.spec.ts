import { expect, test } from "@playwright/test";

const baseAudit = {
  taskId: "11111111-1111-1111-1111-111111111111",
  batchId: "22222222-2222-2222-2222-222222222222",
  containerAssetId: "33333333-3333-3333-3333-333333333333",
  expectedRequirements: [],
  findings: [],
  blockingReasons: [],
};

test("an online return audit starts, scans and completes", async ({ page }) => {
  await page.route("**/api/v1/**", (route) =>
    route.fulfill({
      status: 404,
      contentType: "application/problem+json",
      body: JSON.stringify({ title: "Not found", status: 404 }),
    }),
  );
  await page.route("**/api/v1/session", (route) =>
    route.fulfill({
      json: {
        userId: "44444444-4444-4444-4444-444444444444",
        username: "operator",
        displayName: "Audit operator",
        organizationId: "55555555-5555-5555-5555-555555555555",
        role: "OPERATOR_AUDITOR",
      },
    }),
  );
  await page.route("**/api/v1/application", (route) =>
    route.fulfill({
      json: { applicationName: "Tarpeisto", version: "test", oidcConfigured: false },
    }),
  );
  await page.route("**/api/v1/assets/33333333-3333-3333-3333-333333333333", (route) =>
    route.fulfill({
      json: {
        id: "33333333-3333-3333-3333-333333333333",
        displayName: "Audit case",
        publicCode: "7K3MXY",
      },
    }),
  );
  await page.route("**/api/v1/audits/tasks/11111111-1111-1111-1111-111111111111", (route) =>
    route.fulfill({ json: { ...baseAudit, state: "READY", scans: [] } }),
  );
  await page.route("**/api/v1/audits/tasks/*/start", (route) =>
    route.fulfill({
      json: {
        ...baseAudit,
        id: "66666666-6666-6666-6666-666666666666",
        state: "IN_PROGRESS",
        scans: [],
      },
    }),
  );
  await page.route("**/api/v1/audits/*/scans", (route) =>
    route.fulfill({
      json: {
        ...baseAudit,
        id: "66666666-6666-6666-6666-666666666666",
        state: "IN_PROGRESS",
        scans: [
          {
            id: "77777777-7777-7777-7777-777777777777",
            assetId: "88888888-8888-8888-8888-888888888888",
            assetCode: "7K3MXY",
            outcome: "EXTRA",
            scannedAt: "2026-09-26T21:00:00Z",
            undone: false,
            contextSnapshot: JSON.stringify({ modelName: "LAN cable 10m" }),
          },
        ],
      },
    }),
  );
  await page.route("**/api/v1/audits/*/complete", (route) =>
    route.fulfill({
      json: {
        ...baseAudit,
        id: "66666666-6666-6666-6666-666666666666",
        state: "COMPLETED",
        completionOutcome: "CLEAN",
        scans: [],
      },
    }),
  );

  await page.goto("/audits/tasks/11111111-1111-1111-1111-111111111111");

  await expect(page.getByRole("heading", { name: "Container audit: Audit case" })).toBeVisible();
  await expect(page.getByText(/saved on this device/)).toBeVisible();

  await page.getByLabel("Scan assigned container to start").fill("7K3MXY");
  await page.getByRole("button", { name: "Start audit" }).click();
  await expect(page.getByRole("heading", { name: "Last scans" })).toBeVisible();

  await page.getByLabel("Scan or enter item code").fill("7K3MXY");
  await page.getByRole("button", { name: "Record scan" }).click();
  await expect(page.getByText(/LAN cable 10m - 7K3MXY/)).toBeVisible();

  await page.getByLabel("Scan or enter item code").fill("7K3MXY");
  await page.getByRole("button", { name: "Complete with this code" }).click();
  await expect(page.getByRole("button", { name: "Complete with this code" })).toBeHidden();
});
