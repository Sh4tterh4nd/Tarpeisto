import { expect, test, type Page } from "@playwright/test";

const org = "22222222-2222-2222-2222-222222222222";
const user = "11111111-1111-1111-1111-111111111111";

async function permanentSession(page: Page) {
  await page.route("**/api/v1/**", (route) =>
    route.fulfill({
      status: 404,
      contentType: "application/problem+json",
      json: { title: "Not found", status: 404 },
    }),
  );
  await page.route("**/api/v1/findings/reconcile-packing*", (route) =>
    route.fulfill({ json: { inspectedCount: 0, dismissedCount: 0, nextCursor: null } }),
  );
  await page.route("**/api/v1/session", (route) =>
    route.fulfill({
      json: {
        userId: user,
        username: "owner",
        displayName: "Owner",
        organizationId: org,
        role: "OWNER",
      },
    }),
  );
  await page.route("**/api/v1/application", (route) =>
    route.fulfill({
      json: { applicationName: "Tarpeisto", version: "test", oidcConfigured: false },
    }),
  );
}

test("workboard pages a queue and opens the next review action", async ({ page }) => {
  await permanentSession(page);
  const asset = "33333333-3333-3333-3333-333333333333";
  const queues = [
    "UPCOMING_EVENTS",
    "OUTSTANDING_CUSTODY",
    "AUDITS",
    "REVIEW",
    "REPAIRS",
    "METADATA",
    "CONTAINERS",
    "LOW_STOCK",
  ];
  const metadata = {
    id: asset,
    label: "AP unit 3",
    code: "7K3MXY",
    state: "ACTIVE",
    reason: "Serial number is missing",
    actionLabel: "Complete metadata",
    actionPath: `/inventory/assets/${asset}`,
    readOnly: false,
  };
  const review = {
    id: "finding-1",
    label: "Network case",
    code: "CASE01",
    state: "MISSING",
    reason: "Missing cable requires a decision",
    actionLabel: "Review finding",
    actionPath: "/review",
    readOnly: false,
  };
  await page.route("**/api/v1/dashboard", (route) =>
    route.fulfill({
      json: {
        queues: queues.map((queue) => ({
          queue,
          count: queue === "METADATA" ? 2 : queue === "REVIEW" ? 1 : 0,
          items: queue === "METADATA" ? [metadata] : queue === "REVIEW" ? [review] : [],
          nextCursor: queue === "METADATA" ? "next-metadata" : null,
        })),
      },
    }),
  );
  await page.route("**/api/v1/dashboard/METADATA?*", (route) => {
    expect(new URL(route.request().url()).searchParams.get("cursor")).toBe("next-metadata");
    return route.fulfill({
      json: {
        queue: "METADATA",
        count: 2,
        items: [
          {
            ...metadata,
            id: "44444444-4444-4444-4444-444444444444",
            label: "AP unit 4",
            code: "SECOND",
          },
        ],
        nextCursor: null,
      },
    });
  });
  await page.route("**/api/v1/findings?*", (route) => route.fulfill({ json: [] }));
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Workboard", exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "Event handoff", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Complete metadata" })).toHaveAttribute(
    "href",
    `/inventory/assets/${asset}`,
  );
  await page.getByRole("button", { name: "Show more missing metadata" }).click();
  await expect(page.getByText("AP unit 4 (SECOND)", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Show more missing metadata" })).toHaveCount(0);
  await page.getByRole("link", { name: "Review finding", exact: true }).click();
  await expect(page).toHaveURL(/\/review$/);
  await expect(page.getByText("No unresolved findings.")).toBeVisible();
});

test("report download recovers after a failure and preserves the CSV bytes", async ({ page }) => {
  await permanentSession(page);
  let requests = 0;
  const content = 'asset_id,name,quantity\r\n"asset-1","\'=SUM(1,1) Δ",1.125\r\n';
  await page.route("**/api/v1/reports/inventory.csv", (route) => {
    requests++;
    return requests === 1
      ? route.fulfill({ status: 503, json: { title: "Report unavailable", status: 503 } })
      : route.fulfill({
          status: 200,
          contentType: "text/csv; charset=utf-8",
          headers: { "Content-Disposition": 'attachment; filename="inventory.csv"' },
          body: content,
        });
  });
  await page.goto("/reports");
  await expect(page.getByRole("heading", { name: "Reports", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Download inventory CSV", exact: true }).click();
  await expect(page.getByText(/report could not be downloaded/i)).toBeVisible();
  const downloaded = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download inventory CSV", exact: true }).click();
  const download = await downloaded;
  expect(download.suggestedFilename()).toBe("tarpeisto-inventory.csv");
  const stream = await download.createReadStream();
  if (!stream) throw new Error("The downloaded report did not expose its bytes.");
  let bytes = "";
  for await (const chunk of stream) bytes += chunk.toString("utf8");
  expect(bytes).toBe(content);
  expect(requests).toBe(2);
  await expect(page.getByRole("button", { name: "Download stock movements CSV" })).toBeEnabled();
});

test("zero stock archives out of normal search and restores the same balance", async ({ page }) => {
  await permanentSession(page);
  const balanceId = "55555555-5555-5555-5555-555555555555";
  let archived = false;
  let version = 4;
  await page.route("**/api/v1/categories*", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/v1/locations*", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/v1/consumable-stock/search?*", (route) => {
    const includeArchived =
      new URL(route.request().url()).searchParams.get("includeArchived") === "true";
    return route.fulfill({
      json: {
        items:
          archived && !includeArchived
            ? []
            : [
                {
                  id: balanceId,
                  assetModelId: "66666666-6666-6666-6666-666666666666",
                  assetModelName: "Tape rolls",
                  categoryName: "Consumables",
                  stockUnitLabel: "rolls",
                  placePath: "HQ / Shelf",
                  quantity: 0,
                  totalOnHand: 0,
                  lowStock: false,
                  archived,
                  version,
                },
              ],
        nextCursor: null,
      },
    });
  });
  await page.route(`**/api/v1/consumable-stock/${balanceId}/archive`, (route) => {
    expect(route.request().method()).toBe("PUT");
    expect(route.request().postDataJSON()).toEqual({
      archived: !archived,
      expectedVersion: version,
    });
    archived = !archived;
    version++;
    return route.fulfill({ json: { id: balanceId, archived, version } });
  });
  await page.goto("/inventory/stock");
  await expect(page.getByText("HQ / Shelf", { exact: false })).toBeVisible();
  await page.getByRole("button", { name: "Archive balance", exact: true }).click();
  await expect(page.getByText("No consumables match these filters.")).toBeVisible();
  await page.getByRole("switch", { name: "Include archived", exact: true }).check();
  await page.getByRole("button", { name: "Restore balance", exact: true }).click();
  await expect(page.getByRole("button", { name: "Archive balance", exact: true })).toBeVisible();
  await page.getByRole("switch", { name: "Include archived", exact: true }).uncheck();
  await expect(page.getByRole("button", { name: "Archive balance", exact: true })).toBeVisible();
  expect(archived).toBe(false);
  expect(version).toBe(6);
});

test("model search clears a previous cursor when filters change and retains sorting", async ({
  page,
}) => {
  await permanentSession(page);
  await page.route("**/api/v1/categories*", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/v1/asset-models/search?*", (route) => {
    const params = new URL(route.request().url()).searchParams;
    const filtered = params.get("query") === "fresh";
    if (filtered) expect(params.get("cursor")).toBeNull();
    return route.fulfill({
      json: {
        items: [
          {
            id: filtered
              ? "77777777-7777-7777-7777-777777777777"
              : "88888888-8888-8888-8888-888888888888",
            name: filtered ? "Fresh model" : "Previous model",
            trackingMode: "SERIALIZED_ASSET",
            categoryName: "Default",
            archived: false,
          },
        ],
        nextCursor: filtered ? null : "old-query-cursor",
      },
    });
  });
  await page.goto("/inventory");
  await expect(page.getByRole("link", { name: "Previous model", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Load more models" })).toBeVisible();
  await page.getByRole("textbox", { name: "Search models", exact: true }).fill("fresh");
  await expect(page.getByRole("link", { name: "Previous model", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Load more models" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Fresh model", exact: true })).toBeVisible();
  const sorted = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return (
      url.pathname.endsWith("/asset-models/search") &&
      url.searchParams.get("sort") === "name" &&
      url.searchParams.get("direction") === "desc"
    );
  });
  await page.getByRole("button", { name: "Sort by name", exact: true }).click();
  await sorted;
  await expect(page.getByRole("link", { name: "Fresh model", exact: true })).toBeVisible();
});

test("restoring an archived user requires an explicit enable action", async ({ page }) => {
  await permanentSession(page);
  const targetId = "99999999-9999-9999-9999-999999999999";
  let archived = false;
  let enabled = true;
  let version = 0;
  await page.route("**/api/v1/users?*", (route) => {
    const includeArchived =
      new URL(route.request().url()).searchParams.get("includeArchived") === "true";
    return route.fulfill({
      json:
        archived && !includeArchived
          ? []
          : [
              {
                id: targetId,
                username: "equipment-helper",
                displayName: "Equipment helper",
                email: null,
                role: "OPERATOR_AUDITOR",
                enabled,
                archived,
                version,
              },
            ],
    });
  });
  await page.route(`**/api/v1/users/${targetId}/archive`, (route) => {
    expect(route.request().postDataJSON()).toEqual({
      archived: !archived,
      expectedVersion: version,
    });
    archived = !archived;
    enabled = false;
    version++;
    return route.fulfill({ json: { id: targetId, archived, version } });
  });
  await page.route(`**/api/v1/users/${targetId}/enabled`, (route) => {
    expect(route.request().postDataJSON()).toEqual({ enabled: true });
    enabled = true;
    return route.fulfill({ status: 204 });
  });
  await page.goto("/admin/users");
  await expect(page.getByRole("switch", { name: "Enabled for equipment-helper" })).toBeChecked();
  await page.getByRole("button", { name: "Archive user", exact: true }).click();
  await expect(page.getByText("equipment-helper", { exact: true })).toHaveCount(0);
  await page.getByRole("switch", { name: "Include archived", exact: true }).check();
  await expect(page.getByRole("switch", { name: "Enabled for equipment-helper" })).toBeDisabled();
  await page.getByRole("button", { name: "Restore user", exact: true }).click();
  const enable = page.getByRole("switch", { name: "Enabled for equipment-helper" });
  await expect(enable).toBeEnabled();
  await expect(enable).not.toBeChecked();
  await enable.click();
  await expect(enable).toBeChecked();
  expect(archived).toBe(false);
  expect(enabled).toBe(true);
});

test("owner workboard reconciles every stale packing page before loading review counts", async ({
  page,
}) => {
  await permanentSession(page);
  const calls: string[] = [];
  let stale = 105;
  await page.route("**/api/v1/findings/reconcile-packing*", async (route) => {
    expect(route.request().method()).toBe("POST");
    const cursor = new URL(route.request().url()).searchParams.get("cursor");
    calls.push(cursor ? "sweep-two" : "sweep-one");
    if (!cursor) {
      stale -= 100;
      return route.fulfill({
        json: { inspectedCount: 100, dismissedCount: 100, nextCursor: "last-five" },
      });
    }
    expect(cursor).toBe("last-five");
    stale = 0;
    return route.fulfill({ json: { inspectedCount: 5, dismissedCount: 5, nextCursor: null } });
  });
  await page.route("**/api/v1/dashboard", (route) => {
    calls.push("dashboard");
    expect(stale).toBe(0);
    return route.fulfill({
      json: { queues: [{ queue: "REVIEW", count: stale, items: [], nextCursor: null }] },
    });
  });
  await page.goto("/");
  await expect(page.getByText("No findings need review.")).toBeVisible();
  await expect(page.getByRole("link", { name: "Review finding" })).toHaveCount(0);
  expect(calls).toEqual(["sweep-one", "sweep-two", "dashboard"]);
});

test("Viewer and operator workboards only read queues without a reconciliation POST", async ({
  page,
}) => {
  await permanentSession(page);
  let posts = 0;
  await page.route("**/api/v1/findings/reconcile-packing*", (route) => {
    posts++;
    return route.fulfill({ json: { inspectedCount: 0, dismissedCount: 0 } });
  });
  await page.route("**/api/v1/dashboard", (route) =>
    route.fulfill({ json: { queues: [{ queue: "REVIEW", count: 0, items: [] }] } }),
  );
  for (const role of ["VIEWER", "OPERATOR_AUDITOR"]) {
    await page.route("**/api/v1/session", (route) =>
      route.fulfill({
        json: {
          userId: user,
          username: "reader",
          displayName: "Reader",
          organizationId: org,
          role,
        },
      }),
    );
    await page.goto("/");
    await expect(page.getByText("No findings need review.")).toBeVisible();
  }
  expect(posts).toBe(0);
});
