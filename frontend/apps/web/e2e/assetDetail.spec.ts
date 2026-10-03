import { expect, test, type Page } from "@playwright/test";
const id = "33333333-3333-3333-3333-333333333333",
  modelId = "44444444-4444-4444-4444-444444444444";
async function setup(page: Page, role = "OWNER") {
  let name = "Mobile network case",
    saves = 0,
    archived = false,
    color = "#FFFFFF",
    description: string | null = null,
    version = 7,
    packingSaves = 0;
  const asset = () => ({
    id,
    assetModelId: modelId,
    assetModelName: "Network case",
    publicCode: "7K3MXY",
    unitNumber: 1,
    individualName: name,
    displayName: name,
    condition: "GOOD",
    lifecycleState: "ACTIVE",
    archived,
    metadataIncomplete: false,
    sealable: true,
    sealState: "INVALIDATED",
    values: [],
    purchaseDate: "2026-01-02",
    containerColor: color,
    unitDescription: description,
    version,
  });
  const named = (key: string) => ({
    assetId: key,
    publicCode: key,
    displayName: "Cable " + key,
    assetModelId: "cable",
    assetModelName: "Cable",
    active: true,
  });
  const contents = {
    containerAssetId: id,
    complete: false,
    totalAssetCount: 3,
    totalRequirementCount: 1,
    totalConsumableCount: 1,
    matchedCount: 2,
    extraCount: 0,
    misplacedCount: 0,
    inactiveCount: 1,
    requirements: [
      {
        id: "requirement",
        type: "MODEL_QUANTITY",
        assetModelName: "Cable",
        requiredQuantity: 3,
        presentQuantity: 2,
        missingQuantity: 1,
      },
    ],
    assets: [
      { asset: named("inactive"), status: "INACTIVE" },
      { asset: named("CODE1"), status: "MATCHED", requirementId: "requirement" },
    ],
    consumables: [
      { assetModelId: "tape", assetModelName: "Tape", unitLabel: "rolls", quantity: 1.125 },
    ],
    nextCursor: "page2",
  };
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request(),
      url = new URL(request.url()),
      path = url.pathname;
    if (path === "/api/v1/session") {
      await route.fulfill({
        json: {
          userId: "11111111-1111-1111-1111-111111111111",
          username: "member",
          displayName: "Member",
          organizationId: "22222222-2222-2222-2222-222222222222",
          role,
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
    if (path === `/api/v1/assets/${id}`) {
      await route.fulfill({ json: asset() });
      return;
    }
    if (path === `/api/v1/assets/${id}/name`) {
      saves++;
      if (saves === 1) {
        await route.fulfill({
          status: 409,
          json: {
            type: "test",
            title: "Save failed",
            detail: "Please retry name save",
            status: 409,
          },
        });
        return;
      }
      name = request.postDataJSON().individualName;
      version++;
      await route.fulfill({ json: asset() });
      return;
    }
    if (path === `/api/v1/assets/${id}/packing-details`) {
      const body = request.postDataJSON();
      expect(body.expectedVersion).toBe(version);
      packingSaves++;
      color = body.containerColor;
      description = body.unitDescription || null;
      version++;
      await route.fulfill({ json: asset() });
      return;
    }
    if (path === `/api/v1/assets/${id}/archive`) {
      archived = true;
      await route.fulfill({ status: 204 });
      return;
    }
    if (path === `/api/v1/asset-models/${modelId}`) {
      await route.fulfill({
        json: {
          id: modelId,
          name: "Network case",
          description: "CAT6 patch cables\n\nRed and blue.\n1 metre",
          canContainAssets: true,
        },
      });
      return;
    }
    if (path === `/api/v1/assets/${id}/placement`) {
      await route.fulfill({
        json: {
          assetId: id,
          version: 7,
          directLocationId: null,
          parentContainerAssetId: null,
          effectivePath: [name],
          effectivePathText: name,
        },
      });
      return;
    }
    if (path === `/api/v1/assets/${id}/packing-contents`) {
      await route.fulfill({
        json: url.searchParams.has("cursor")
          ? {
              ...contents,
              requirements: [],
              assets: [{ asset: named("CODE2"), status: "MATCHED", requirementId: "requirement" }],
              consumables: [],
              nextCursor: null,
            }
          : contents,
      });
      return;
    }
    if (path === `/api/v1/assets/${id}/packing-sheet.pdf`) {
      await route.fulfill({
        contentType: "application/pdf",
        body: "%PDF-1.4\n% packing contents\n",
      });
      return;
    }
    if (path === `/api/v1/assets/${id}/history`) {
      await route.fulfill({
        json: [
          {
            id: "history",
            changeType: "CONDITION",
            previousValue: "DAMAGED",
            newValue: "GOOD",
            changedAt: "2026-01-01T10:00:00Z",
            reason: "Repair completed",
          },
        ],
      });
      return;
    }
    if (path.endsWith("/media/reference")) {
      await route.fulfill({ status: 404 });
      return;
    }
    if (path === "/api/v1/assets") {
      await route.fulfill({ json: { items: [], nextCursor: null } });
      return;
    }
    if (path === `/api/v1/assets/${id}/packing-preview`) {
      await route.fulfill({
        json: {
          complete: false,
          satisfiedRequirementIds: [],
          missingRequirementIds: [],
          extraAssetIds: [],
          misplacedAssetIds: [],
          consumables: [],
        },
      });
      return;
    }
    if (request.method() === "GET") {
      await route.fulfill({ json: [] });
      return;
    }
    await route.fulfill({ status: 404, json: { type: "test", title: "Not found", status: 404 } });
  });
  return {
    remotePackingChange() {
      color = "#0078A5";
      description = "Remote instructions";
      version++;
    },
    packingSaves: () => packingSaves,
  };
}

test("asset detail stays read only until Edit, groups contents and preserves section failure drafts", async ({
  page,
}) => {
  await setup(page);
  await page.goto(`/inventory/assets/${id}`);
  await expect(page.getByRole("heading", { name: "Mobile network case" })).toBeVisible();
  await expect(page.getByText("CAT6 patch cables, Red and blue. 1 metre")).toBeVisible();
  await expect(page.getByRole("textbox")).toHaveCount(0);
  await expect(page.getByText("Invalidated - verification required")).toBeVisible();
  await expect(page.getByText("Inactive", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: /2.*Cable.*1 missing/ }).click();
  await expect(page.getByRole("link", { name: "Cable CODE1 CODE1" })).toBeVisible();
  await page.getByRole("button", { name: "Show more contents and requirements" }).click();
  await expect(page.getByRole("link", { name: "Cable CODE2 CODE2" })).toBeVisible();
  await expect(page.getByText("1.125 rolls / Tape")).toBeVisible();
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download packing sheet" }).click();
  expect((await downloadPromise).suggestedFilename()).toContain(id);
  await page.getByRole("button", { name: "Asset actions" }).click();
  await page.getByRole("menuitem", { name: "Edit", exact: true }).click();
  const name = page.getByRole("textbox", { name: "Individual name (optional)" });
  await name.fill("New case name");
  await page.getByRole("button", { name: "Save name", exact: true }).click();
  await expect(page.getByText("Please retry name save")).toBeVisible();
  await expect(name).toHaveValue("New case name");
  await page.getByRole("button", { name: "Save name", exact: true }).click();
  await expect(page.getByRole("heading", { name: "New case name" })).toBeVisible();
  await name.fill("Unsaved name");
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(page.getByRole("textbox")).toHaveCount(0);
  await page.getByRole("button", { name: "Asset actions" }).click();
  await page.getByRole("menuitem", { name: "Archive", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "Archive asset?" })).toBeVisible();
  await page.getByRole("button", { name: "Archive asset", exact: true }).click();
  await expect(page.getByText("This asset is archived.")).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("Viewer can inspect condition and seal history without mutation forms", async ({ page }) => {
  await setup(page, "VIEWER");
  await page.goto(`/inventory/assets/${id}`);
  await page.getByRole("button", { name: "View history" }).click();
  const history = page.getByRole("dialog", { name: "History" });
  await expect(history.getByText(/Repair completed/)).toBeVisible();
  await expect(history.getByRole("textbox")).toHaveCount(0);
  await expect(history.getByRole("combobox")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Asset actions" })).toHaveCount(0);
  await history.getByRole("button", { name: "Done" }).click();
  await expect(page.getByRole("button", { name: "Download packing sheet" })).toBeVisible();
});

test("container packing color and description save after name edits and stay readonly outside Edit", async ({
  page,
}, testInfo) => {
  const backend = await setup(page);
  await page.goto(`/inventory/assets/${id}`);
  await page.getByRole("button", { name: "Asset actions" }).click();
  await page.getByRole("menuitem", { name: "Edit", exact: true }).click();
  await page
    .getByRole("textbox", { name: "Individual name (optional)" })
    .fill("Cable service case");
  await page.getByRole("button", { name: "Save name", exact: true }).click();
  await expect(page.getByText("Please retry name save")).toBeVisible();
  await page.getByRole("button", { name: "Save name", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Cable service case" })).toBeVisible();
  await page.getByRole("textbox", { name: "Packing header color", exact: true }).fill("112233");
  await page
    .getByRole("textbox", { name: "Container description" })
    .fill("Dedicated cable set\nKeep the labels visible");
  const preview = page.getByLabel("Packing header preview");
  await expect(preview).toHaveCSS("background-color", "rgb(17, 34, 51)");
  await expect(preview).toHaveCSS("color", "rgb(255, 255, 255)");
  await preview.scrollIntoViewIfNeeded();
  await page.screenshot({
    path: testInfo.outputPath("packing-editor.png"),
    animations: "disabled",
  });
  await page.getByRole("button", { name: "Save packing details" }).click();
  await expect(page.getByText("Packing details saved.")).toBeVisible();
  expect(backend.packingSaves()).toBe(1);
  await page.getByRole("button", { name: "Done", exact: true }).click();
  await expect(page.getByText("Dedicated cable set", { exact: false })).toBeVisible();
  await expect(page.getByRole("textbox")).toHaveCount(0);
  await expect(page.getByText("Packing header: #112233")).toBeVisible();
});

test("container remote packing edits retain the local draft until explicit reload", async ({
  page,
}) => {
  const backend = await setup(page);
  await page.goto(`/inventory/assets/${id}`);
  await page.getByRole("button", { name: "Asset actions" }).click();
  await page.getByRole("menuitem", { name: "Edit", exact: true }).click();
  const description = page.getByRole("textbox", { name: "Container description" });
  await description.fill("My local instructions");
  backend.remotePackingChange();
  await page.getByRole("button", { name: "Save packing details" }).click();
  await expect(page.getByText(/Packing details changed elsewhere/)).toBeVisible();
  await expect(description).toHaveValue("My local instructions");
  expect(backend.packingSaves()).toBe(0);
  await page.getByRole("button", { name: "Reload packing details" }).click();
  await expect(description).toHaveValue("Remote instructions");
  await expect(page.getByLabel("Packing header preview")).toHaveCSS("color", "rgb(0, 0, 0)");
  await description.fill("Remote instructions reviewed");
  await page.getByRole("button", { name: "Save packing details" }).click();
  await expect(page.getByText("Packing details saved.")).toBeVisible();
  expect(backend.packingSaves()).toBe(1);
});
