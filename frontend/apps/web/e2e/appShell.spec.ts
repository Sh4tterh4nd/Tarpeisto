import { expect, test } from "@playwright/test";

// Phase 0 smoke test: the installed app shell loads, its two routes are
// reachable, and the client-side asset-code check works without depending on
// the backend being available (journey tests belong to later phases).
test("app shell loads, shows connectivity status, and the asset-code check works", async ({
  page,
}) => {
  await page.goto("/");

  await expect(page.getByRole("heading", { name: "BigContainers" })).toBeVisible();
  await expect(page.getByRole("status").first()).toBeVisible();

  await page.getByRole("link", { name: "Check code" }).click();
  await expect(page.getByRole("heading", { name: "Check an asset code" })).toBeVisible();

  await page.getByLabel("Public asset code").fill("7K3MXY");
  await page.getByRole("button", { name: "Validate code" }).click();

  await expect(page.getByRole("status").filter({ hasText: "checks out" })).toContainText(
    "7K3MXY checks out.",
  );
});
