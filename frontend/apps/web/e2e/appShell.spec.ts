import { expect, test } from "@playwright/test";

// Phase 6 smoke test: an authenticated inventory user can reach the scanner's
// permanent manual fallback and resolve a valid organization-scoped asset code.
test("app shell loads and a valid public code resolves to an asset", async ({ page }) => {
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
        userId: "11111111-1111-1111-1111-111111111111",
        username: "owner",
        displayName: "Owner",
        organizationId: "22222222-2222-2222-2222-222222222222",
        role: "OWNER",
      },
    }),
  );
  await page.route("**/api/v1/application", (route) =>
    route.fulfill({
      json: { applicationName: "Tarpeisto", version: "test", oidcConfigured: false },
    }),
  );
  await page.route("**/api/v1/assets/by-code/7K3MXY", (route) =>
    route.fulfill({
      json: {
        id: "33333333-3333-3333-3333-333333333333",
        assetModelId: "44444444-4444-4444-4444-444444444444",
        assetModelName: "UniFi AP-HD",
        publicCode: "7K3MXY",
        unitNumber: 3,
        displayName: "UniFi AP-HD 3",
        condition: "GOOD",
        lifecycleState: "ACTIVE",
        archived: false,
        metadataIncomplete: false,
        values: [],
        createdAt: "2026-09-26T10:00:00Z",
        updatedAt: "2026-09-26T10:00:00Z",
      },
    }),
  );
  await page.route("**/api/v1/assets/33333333-3333-3333-3333-333333333333", (route) =>
    route.fulfill({
      json: {
        id: "33333333-3333-3333-3333-333333333333",
        assetModelId: "44444444-4444-4444-4444-444444444444",
        assetModelName: "UniFi AP-HD",
        publicCode: "7K3MXY",
        unitNumber: 3,
        displayName: "UniFi AP-HD 3",
        condition: "GOOD",
        lifecycleState: "ACTIVE",
        archived: false,
        metadataIncomplete: false,
        values: [],
        createdAt: "2026-09-26T10:00:00Z",
        updatedAt: "2026-09-26T10:00:00Z",
      },
    }),
  );
  await page.route("**/api/v1/asset-models/44444444-4444-4444-4444-444444444444", (route) =>
    route.fulfill({ json: { canContainAssets: false } }),
  );

  await page.goto("/");

  await expect(page.getByRole("heading", { name: "Tarpeisto" })).toBeVisible();
  await expect(page.getByRole("status").first()).toBeVisible();

  if (!(await page.getByRole("link", { name: "Scan equipment" }).isVisible())) {
    await page.getByRole("button", { name: "Open navigation" }).click();
  }
  await page.getByRole("link", { name: "Scan equipment" }).click();
  await expect(page.getByRole("heading", { name: "Scan equipment" })).toBeVisible();

  await page.getByLabel("Public asset code").fill("7k3-mxy");
  await page.getByRole("button", { name: "Open result" }).click();

  await expect(page.getByRole("heading", { name: "UniFi AP-HD 3" })).toBeVisible();
  await expect(page.getByText("7K3MXY", { exact: true })).toBeVisible();
});
