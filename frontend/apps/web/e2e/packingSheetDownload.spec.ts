import { expect, test } from "@playwright/test";

const assetId = "33333333-3333-3333-3333-333333333333";
const assetModelId = "44444444-4444-4444-4444-444444444444";

const asset = {
  id: assetId,
  assetModelId,
  assetModelName: "RAKO 400 x 300",
  publicCode: "7K3MXY",
  unitNumber: 1,
  individualName: "Mobile Network Box",
  displayName: "Mobile Network Box",
  condition: "GOOD",
  lifecycleState: "ACTIVE",
  archived: false,
  metadataIncomplete: false,
  sealable: false,
  sealState: "UNSEALED",
  version: 1,
  values: [],
  createdAt: "2026-09-27T10:00:00Z",
  updatedAt: "2026-09-27T10:00:00Z",
};

const containerModel = {
  id: assetModelId,
  name: "RAKO 400 x 300",
  description: "",
  categoryId: "55555555-5555-5555-5555-555555555555",
  replacementUrl: "",
  trackingMode: "SERIALIZED_ASSET",
  canContainAssets: true,
  archived: false,
  createdAt: "2026-09-27T10:00:00Z",
  updatedAt: "2026-09-27T10:00:00Z",
  version: 1,
};

test("an owner downloads a container packing sheet", async ({ page }) => {
  let packingSheetRequests = 0;
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/v1/session") {
      await route.fulfill({
        json: {
          userId: "11111111-1111-1111-1111-111111111111",
          username: "owner",
          displayName: "Packing owner",
          organizationId: "22222222-2222-2222-2222-222222222222",
          role: "OWNER",
        },
      });
      return;
    }
    if (path === "/api/v1/application") {
      await route.fulfill({
        json: { applicationName: "Tarpeisto", version: "test", oidcConfigured: false },
      });
      return;
    }
    if (path === `/api/v1/assets/${assetId}/packing-sheet.pdf`) {
      expect(request.method()).toBe("GET");
      packingSheetRequests++;
      await route.fulfill({
        status: 200,
        contentType: "application/pdf",
        body: "%PDF-1.4\n% mocked packing sheet\n",
      });
      return;
    }
    if (path === `/api/v1/assets/${assetId}`) {
      await route.fulfill({ json: asset });
      return;
    }
    if (path === `/api/v1/assets/${assetId}/history`) {
      await route.fulfill({ json: [] });
      return;
    }
    if (path === `/api/v1/asset-models/${assetModelId}`) {
      await route.fulfill({ json: containerModel });
      return;
    }
    if (path === "/api/v1/asset-models") {
      await route.fulfill({ json: [containerModel] });
      return;
    }
    if (path === `/api/v1/asset-models/${assetModelId}/assets`) {
      await route.fulfill({ json: [asset] });
      return;
    }
    if (path === `/api/v1/assets/${assetId}/placement`) {
      await route.fulfill({
        json: {
          assetId,
          directLocationId: "",
          parentContainerAssetId: "",
          version: 1,
          effectivePath: [],
          effectivePathText: "Unplaced",
        },
      });
      return;
    }
    if (path === "/api/v1/locations") {
      await route.fulfill({ json: [] });
      return;
    }
    if (
      path === `/api/v1/assets/${assetId}/contents` ||
      path === `/api/v1/assets/${assetId}/consumable-stock` ||
      path === `/api/v1/assets/${assetId}/packing-requirements` ||
      path === "/api/v1/packing-templates" ||
      path === `/api/v1/assets/${assetId}/repairs` ||
      path === `/api/v1/assets/${assetId}/seal/history` ||
      path === `/api/v1/assets/${assetId}/media/layout`
    ) {
      await route.fulfill({ json: [] });
      return;
    }
    if (path === `/api/v1/assets/${assetId}/packing-contents`) {
      await route.fulfill({
        json: {
          containerAssetId: assetId,
          complete: true,
          totalAssetCount: 0,
          totalRequirementCount: 0,
          totalConsumableCount: 0,
          matchedCount: 0,
          extraCount: 0,
          misplacedCount: 0,
          inactiveCount: 0,
          requirements: [],
          assets: [],
          consumables: [],
          nextCursor: null,
        },
      });
      return;
    }
    if (path === `/api/v1/assets/${assetId}/packing-preview`) {
      await route.fulfill({
        json: {
          complete: true,
          satisfiedRequirementIds: [],
          missingRequirementIds: [],
          extraAssetIds: [],
          misplacedAssetIds: [],
          consumables: [],
        },
      });
      return;
    }
    await route.fulfill({
      status: 404,
      contentType: "application/problem+json",
      body: JSON.stringify({ title: "Not found", status: 404 }),
    });
  });

  await page.goto(`/inventory/assets/${assetId}`);
  await expect(page.getByRole("heading", { name: "Direct contents" })).toBeVisible();

  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download packing sheet" }).click();
  const download = await downloadPromise;

  expect(packingSheetRequests).toBe(1);
  expect(download.suggestedFilename()).toBe(`packing-sheet-${assetId}.pdf`);
});
