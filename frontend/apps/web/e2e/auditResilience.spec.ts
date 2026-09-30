import { expect, test, type BrowserContext, type Page } from "@playwright/test";

const taskId = "11111111-1111-1111-1111-111111111111";
const auditId = "66666666-6666-6666-6666-666666666666";
const containerId = "33333333-3333-3333-3333-333333333333";
const userId = "44444444-4444-4444-4444-444444444444";
const organizationId = "55555555-5555-5555-5555-555555555555";
const actor = `${organizationId}:${userId}`;
const path = `/audits/tasks/${taskId}`;
const png = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jKe0AAAAASUVORK5CYII=",
  "base64",
);

function serverState() {
  return {
    available: true,
    currentUser: userId,
    loseScanResponse: false,
    losePhotoResponse: false,
    calls: [] as { operationId: string; kind: string; actor: string | undefined }[],
    scanIds: new Set<string>(),
    photoIds: new Set<string>(),
    concurrent: 0,
    maxConcurrent: 0,
    audit: {
      id: auditId,
      taskId,
      batchId: "22222222-2222-2222-2222-222222222222",
      containerAssetId: containerId,
      state: "IN_PROGRESS",
      expectedRequirements: [],
      blockingReasons: [],
      scans: [] as Record<string, unknown>[],
      findings: [] as Record<string, unknown>[],
    },
    evidence: [] as Record<string, unknown>[],
  };
}

async function mockServer(context: BrowserContext, state: ReturnType<typeof serverState>) {
  await context.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (!state.available) {
      await route.abort("internetdisconnected");
      return;
    }
    if (url.pathname === "/api/v1/session") {
      await route.fulfill({
        json: {
          userId: state.currentUser,
          username: "operator",
          displayName: "Audit operator",
          organizationId,
          role: "OPERATOR_AUDITOR",
        },
      });
      return;
    }
    if (url.pathname === "/api/v1/application") {
      await route.fulfill({
        json: { applicationName: "Tarpeisto", version: "test", oidcConfigured: false },
      });
      return;
    }
    if (state.currentUser !== userId) {
      await route.fulfill({ status: 404, json: { title: "Not found", status: 404 } });
      return;
    }
    if (url.pathname === `/api/v1/audits/tasks/${taskId}/container`) {
      await route.fulfill({
        json: { id: containerId, displayName: "Resilient case", publicCode: "7K3MXY" },
      });
      return;
    }
    if (url.pathname === `/api/v1/audits/tasks/${taskId}`) {
      await route.fulfill({ json: state.audit });
      return;
    }
    if (url.pathname === `/api/v1/audits/${auditId}/evidence` && request.method() === "GET") {
      await route.fulfill({ json: state.evidence });
      return;
    }
    if (url.pathname.startsWith("/api/v1/media/")) {
      await route.fulfill({ contentType: "image/png", body: png });
      return;
    }
    if (request.method() === "POST" && url.pathname.startsWith(`/api/v1/audits/${auditId}/`)) {
      const photo = url.pathname.endsWith("/evidence");
      const body = photo ? {} : (request.postDataJSON() as Record<string, string>);
      const operationId = photo ? url.searchParams.get("operationId")! : body["operationId"]!;
      const kind = photo ? "photo" : url.pathname.split("/").at(-1)!;
      state.calls.push({ operationId, kind, actor: request.headers()["x-tarpeisto-audit-actor"] });
      if (request.headers()["x-tarpeisto-audit-actor"] !== actor) {
        await route.fulfill({
          status: 409,
          json: { title: "Session changed", status: 409, errorCode: "AUDIT_ACTOR_CHANGED" },
        });
        return;
      }
      state.concurrent++;
      state.maxConcurrent = Math.max(state.maxConcurrent, state.concurrent);
      await new Promise((resolve) => setTimeout(resolve, 80));
      state.concurrent--;
      if (kind === "scans" && !state.scanIds.has(operationId)) {
        state.scanIds.add(operationId);
        state.audit.scans.push({
          id: operationId,
          operationId,
          assetId: "88888888-8888-8888-8888-888888888888",
          assetCode: body["code"],
          outcome: "EXTRA",
          scannedAt: "2026-09-30T00:00:00Z",
          undone: false,
          contextSnapshot: JSON.stringify({ modelName: "Cable" }),
        });
      }
      if (
        kind === "findings" &&
        !state.audit.findings.some((finding) => finding["sourceOperationId"] === operationId)
      ) {
        state.audit.findings.push({
          id: operationId,
          sourceOperationId: operationId,
          type: body["type"],
          assetId: body["assetId"],
          note: body["note"],
          detail: "{}",
        });
      }
      if (photo && !state.photoIds.has(operationId)) {
        const findingId = url.pathname.split("/").at(-2)!;
        state.photoIds.add(operationId);
        state.evidence.push({
          id: operationId,
          auditId,
          findingId,
          uploadOperationId: operationId,
          purpose: "AUDIT_EVIDENCE",
          contentType: "image/png",
          byteSize: png.length,
          displayOrder: 0,
          primaryImage: false,
          imageUrl: `/api/v1/media/${operationId}`,
          thumbnailUrl: `/api/v1/media/${operationId}/thumbnail`,
          createdAt: "2026-09-30T00:00:00Z",
          version: 0,
        });
      }
      if (kind === "scans" && state.loseScanResponse) {
        state.loseScanResponse = false;
        await route.abort("connectionreset");
        return;
      }
      if (photo && state.losePhotoResponse) {
        state.losePhotoResponse = false;
        await route.abort("connectionreset");
        return;
      }
      if (kind === "complete") state.audit.state = "COMPLETED";
      await route.fulfill({
        json: photo
          ? state.evidence.find((item) => item["uploadOperationId"] === operationId)
          : state.audit,
      });
      return;
    }
    await route.fulfill({ status: 404, json: { title: "Not found", status: 404 } });
  });
}

async function connectivity(page: Page, online: boolean) {
  await page.evaluate((value) => {
    Object.defineProperty(navigator, "onLine", { configurable: true, get: () => value });
    window.dispatchEvent(new Event(value ? "online" : "offline"));
  }, online);
}

async function savedRows(page: Page, store = "commands") {
  return page.evaluate(
    (storeName) =>
      new Promise<Record<string, unknown>[]>((resolve, reject) => {
        const request = indexedDB.open("tarpeisto-active-audit-v1");
        request.onerror = () => reject(request.error);
        request.onsuccess = () => {
          const database = request.result;
          const read = database.transaction(storeName).objectStore(storeName).getAll();
          read.onsuccess = () => {
            database.close();
            resolve(read.result as Record<string, unknown>[]);
          };
          read.onerror = () => {
            database.close();
            reject(read.error);
          };
        };
      }),
    store,
  );
}

test("lost scan response and offline reload recover stable FIFO work across two tabs", async ({
  context,
  page,
}) => {
  const state = serverState();
  state.loseScanResponse = true;
  await mockServer(context, state);
  await page.goto(path);
  await expect(
    page.getByRole("heading", { name: "Container audit: Resilient case" }),
  ).toBeVisible();
  await page.getByLabel("Scan or enter item code").fill("5J3H0F");
  await page.getByRole("button", { name: "Record scan" }).click();
  await expect.poll(() => state.calls.length).toBe(1);
  state.available = false;
  await connectivity(page, false);
  await page.getByLabel("Scan or enter item code").fill("7K3MXY");
  await page.getByRole("button", { name: "Record scan" }).click();
  await expect.poll(async () => (await savedRows(page)).length).toBe(2);
  const originalIds = (await savedRows(page)).map((row) => row["operationId"]);
  await expect(page.getByRole("button", { name: "Complete with this code" })).toBeDisabled();
  await page.addInitScript(() =>
    Object.defineProperty(navigator, "onLine", { configurable: true, get: () => false }),
  );
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Container audit: Resilient case" }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Saved work awaiting synchronization" }),
  ).toBeVisible();
  const second = await context.newPage();
  await second.addInitScript(() =>
    Object.defineProperty(navigator, "onLine", { configurable: true, get: () => false }),
  );
  await second.goto(path);
  await expect(
    second.getByRole("heading", { name: "Container audit: Resilient case" }),
  ).toBeVisible();
  state.available = true;
  await connectivity(page, true);
  await connectivity(second, true);
  await expect.poll(async () => (await savedRows(page)).length, { timeout: 15_000 }).toBe(0);
  expect([...state.scanIds]).toEqual(originalIds);
  expect(
    state.calls.filter((call) => call.kind === "scans").map((call) => call.operationId),
  ).toEqual([originalIds[0], ...originalIds]);
  expect(state.maxConcurrent).toBe(1);
  await expect(page.getByRole("button", { name: "Complete with this code" })).toBeEnabled();
});

test("finding photographs persist with bytes across reload and uncertain upload retries", async ({
  context,
  page,
}) => {
  const state = serverState();
  state.losePhotoResponse = true;
  await mockServer(context, state);
  await page.goto(path);
  await expect(
    page.getByRole("heading", { name: "Container audit: Resilient case" }),
  ).toBeVisible();
  state.available = false;
  await connectivity(page, false);
  await page.getByLabel("Damage note").fill("Unknown item with a broken connector");
  await page
    .locator('input[type="file"]')
    .setInputFiles({ name: "connector.png", mimeType: "image/png", buffer: png });
  await page.getByRole("button", { name: "Report unknown item" }).click();
  await expect.poll(async () => (await savedRows(page)).length).toBe(2);
  await expect.poll(async () => (await savedRows(page, "blobs")).length).toBe(1);
  const pending = await savedRows(page);
  const findingOperation = pending[0]!["operationId"];
  const uploadOperation = pending[1]!["operationId"];
  await expect(page.getByRole("button", { name: "Complete with this code" })).toBeDisabled();
  await page.addInitScript(() =>
    Object.defineProperty(navigator, "onLine", { configurable: true, get: () => false }),
  );
  await page.reload();
  await expect(page.getByText(/connector.png/)).toBeVisible();
  state.available = true;
  await connectivity(page, true);
  await expect.poll(async () => (await savedRows(page)).length, { timeout: 15_000 }).toBe(0);
  await expect.poll(async () => (await savedRows(page, "blobs")).length).toBe(0);
  expect(state.audit.findings).toHaveLength(1);
  expect(state.evidence).toHaveLength(1);
  expect(state.evidence[0]!["findingId"]).toBe(findingOperation);
  expect(state.evidence[0]!["uploadOperationId"]).toBe(uploadOperation);
  expect(
    state.calls.filter((call) => call.kind === "photo").map((call) => call.operationId),
  ).toEqual([uploadOperation, uploadOperation]);
  await expect(page.getByAltText("Audit finding evidence")).toBeVisible();
  await page.getByLabel("Scan or enter item code").fill("7K3MXY");
  await page.getByRole("button", { name: "Complete with this code" }).click();
  await expect(page.getByRole("button", { name: "Complete with this code" })).toBeHidden();
});

test("switching accounts stops other tabs and never replays the previous account's saved scans", async ({
  context,
  page,
}) => {
  const state = serverState();
  await mockServer(context, state);
  await page.goto(path);
  await expect(
    page.getByRole("heading", { name: "Container audit: Resilient case" }),
  ).toBeVisible();
  await connectivity(page, false);
  await page.getByLabel("Scan or enter item code").fill("5J3H0F");
  await page.getByRole("button", { name: "Record scan" }).click();
  await expect.poll(async () => (await savedRows(page)).length).toBe(1);
  state.currentUser = "99999999-9999-9999-9999-999999999999";
  const second = await context.newPage();
  await second.goto(path);
  await expect(page.getByRole("heading", { name: "Container audit: Resilient case" })).toBeHidden();
  await expect(
    second.getByRole("heading", { name: "Saved work awaiting synchronization" }),
  ).toBeHidden();
  expect(state.calls).toHaveLength(0);
  expect((await savedRows(second))[0]!["partition"]).toBe(actor);
});
