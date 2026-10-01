import { expect, test } from "@playwright/test";

const finding = {
  id: "11111111-1111-1111-1111-111111111111",
  auditId: "22222222-2222-2222-2222-222222222222",
  assetId: "33333333-3333-3333-3333-333333333333",
  type: "MISSING",
  note: "Network cable is missing",
  detail: "{}",
  recordedAt: "2026-09-26T12:00:00Z",
  resolved: false,
  resolutionAction: "DISMISS",
  resolvedAt: "",
  applicableActions: ["FOUND_AND_RETURNED", "MARK_LOST", "DISMISS"],
  auditTaskId: "44444444-4444-4444-4444-444444444444",
  containerAssetId: "55555555-5555-5555-5555-555555555555",
};

test("an owner confirms a permanent review decision before resolving it", async ({ page }) => {
  await page.route("**/api/v1/**", (route) =>
    route.fulfill({
      status: 404,
      contentType: "application/problem+json",
      body: JSON.stringify({ title: "Not found", status: 404 }),
    }),
  );
  await page.route("**/api/v1/findings/reconcile-packing*", (route) =>
    route.fulfill({ json: { inspectedCount: 0, dismissedCount: 0, nextCursor: null } }),
  );
  await page.route("**/api/v1/session", (route) =>
    route.fulfill({
      json: {
        userId: "66666666-6666-6666-6666-666666666666",
        username: "owner",
        displayName: "Review owner",
        organizationId: "77777777-7777-7777-7777-777777777777",
        role: "OWNER",
      },
    }),
  );
  await page.route("**/api/v1/application", (route) =>
    route.fulfill({
      json: { applicationName: "Tarpeisto", version: "test", oidcConfigured: false },
    }),
  );
  await page.route("**/api/v1/findings?*", (route) => route.fulfill({ json: [finding] }));
  await page.route("**/api/v1/findings/11111111-1111-1111-1111-111111111111", (route) =>
    route.fulfill({ json: finding }),
  );
  await page.route(
    "**/api/v1/findings/11111111-1111-1111-1111-111111111111/resolutions",
    async (route) => {
      const request = route.request();
      expect(request.postDataJSON()).toMatchObject({ action: "MARK_LOST" });
      await route.fulfill({ json: { ...finding, resolved: true, resolutionAction: "MARK_LOST" } });
    },
  );

  await page.goto("/review");
  await expect(page.getByText("Network cable is missing").first()).toBeVisible();
  await page.getByRole("button", { name: /mark lost/i }).click();
  await page.getByRole("button", { name: /save mark lost/i }).click();
  await expect(page.getByText(/confirm the permanent lifecycle change/i)).toBeVisible();
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: /save mark lost/i }).click();
  await expect(page.getByText("No unresolved findings.")).toBeVisible();
});
