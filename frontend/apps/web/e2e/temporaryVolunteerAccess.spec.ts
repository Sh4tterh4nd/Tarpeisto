import { expect, test, type Page } from "@playwright/test";

const taskId = "11111111-1111-1111-1111-111111111111";
const batchId = "22222222-2222-2222-2222-222222222222";
const containerId = "33333333-3333-3333-3333-333333333333";
const auditId = "66666666-6666-6666-6666-666666666666";
const invitationId = "99999999-9999-9999-9999-999999999999";
const token = "A".repeat(43);
const principal = {
  userId: "44444444-4444-4444-4444-444444444444",
  username: "volunteer-browser",
  displayName: "Phone volunteer",
  organizationId: "55555555-5555-5555-5555-555555555555",
  role: "OPERATOR_AUDITOR",
  temporaryAccess: {
    sessionId: "88888888-8888-8888-8888-888888888888",
    invitationId,
    auditBatchId: batchId,
    expiresAt: new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString(),
  },
};
const container = { id: containerId, displayName: "Assigned audit case", publicCode: "7K3MXY" };
const baseAudit = {
  id: auditId,
  taskId,
  batchId,
  containerAssetId: containerId,
  state: "IN_PROGRESS",
  expectedRequirements: [],
  findings: [],
  scans: [],
  blockingReasons: [],
};

async function volunteerBackend(page: Page, initiallySignedIn = false) {
  const state = {
    signedIn: initiallySignedIn,
    revoked: false,
    offline: false,
    displayName: principal.displayName,
    expiresAt: principal.temporaryAccess.expiresAt,
    tasksReady: true,
    scanRequests: 0,
  };
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/v1/application") {
      await route.fulfill({
        json: { applicationName: "Tarpeisto", version: "test", oidcConfigured: false },
      });
    } else if (path === "/api/v1/setup") {
      await route.fulfill({ json: { setupRequired: false } });
    } else if (path === "/api/v1/session") {
      if (state.offline) {
        await route.abort();
        return;
      }
      if (state.signedIn && !state.revoked) {
        await route.fulfill({
          json: {
            ...principal,
            displayName: state.displayName,
            temporaryAccess: { ...principal.temporaryAccess, expiresAt: state.expiresAt },
          },
        });
      } else {
        await route.fulfill({
          status: 401,
          json: { title: "Session expired", status: 401, errorCode: "SESSION_EXPIRED" },
        });
      }
    } else if (path === "/api/v1/temporary-access/redemptions") {
      const body = request.postDataJSON() as {
        token: string;
        displayName: string;
        operationId: string;
      };
      expect(body.token).toBe(token);
      expect(body.operationId).toMatch(/^[0-9a-f-]{36}$/);
      state.signedIn = true;
      state.displayName = body.displayName;
      await route.fulfill({ json: { ...principal, displayName: body.displayName } });
    } else if (path === "/api/v1/temporary-access/tasks") {
      await route.fulfill({
        json: state.tasksReady
          ? [
              {
                taskId,
                batchId,
                containerAssetId: containerId,
                ...container,
                state: "IN_PROGRESS",
                blockingReasons: [],
              },
            ]
          : [],
      });
    } else if (path === `/api/v1/audits/tasks/${taskId}`) {
      await route.fulfill({ json: baseAudit });
    } else if (path === `/api/v1/audits/tasks/${taskId}/container`) {
      await route.fulfill({ json: container });
    } else if (path === `/api/v1/audits/${auditId}/scans`) {
      if (state.offline) {
        await route.abort();
        return;
      }
      const body = request.postDataJSON() as { code: string; operationId: string };
      expect(request.headers()["x-tarpeisto-audit-actor"]).toBe(
        `${principal.organizationId}:${principal.userId}`,
      );
      state.scanRequests += 1;
      await route.fulfill({
        json: {
          ...baseAudit,
          scans: [
            {
              id: "77777777-7777-7777-7777-777777777777",
              operationId: body.operationId,
              assetId: containerId,
              assetCode: body.code,
              outcome: "EXTRA",
              scannedAt: new Date().toISOString(),
              undone: false,
              contextSnapshot: JSON.stringify({ modelName: "Assigned case" }),
            },
          ],
        },
      });
    } else if (path.endsWith("/evidence") || path.endsWith("/media/layout")) {
      await route.fulfill({ json: [] });
    } else {
      await route.fulfill({ status: 404, json: { title: "Not found", status: 404 } });
    }
  });
  return state;
}

test("invitation fragment is removed before API calls and named access has isolated navigation", async ({
  page,
}) => {
  await page.addInitScript(() => {
    const original = window.fetch;
    const observations: string[] = [];
    Object.assign(window, { invitationHashesAtRequest: observations });
    window.fetch = (...args) => {
      observations.push(window.location.hash);
      return original(...args);
    };
  });
  await volunteerBackend(page);
  const inventoryRequests: string[] = [];
  page.on("request", (request) => {
    if (/\/api\/v1\/(assets|asset-models|users|bookings)(?:\?|\/|$)/.test(request.url()))
      inventoryRequests.push(request.url());
  });
  await page.goto(`/join#token=${token}`);
  await expect(page.getByRole("heading", { name: "Join as a volunteer" })).toBeVisible();
  expect(new URL(page.url()).hash).toBe("");
  await page.getByLabel("Your display name").fill("Morgan");
  await page.getByRole("button", { name: "Join audit team" }).click();
  await expect(page.getByRole("heading", { name: "Your assigned audits" })).toBeVisible();
  await expect(page.getByText("Welcome, Morgan.")).toBeVisible();
  await expect(page.getByRole("link", { name: "Inventory", exact: true })).toBeHidden();
  await page.goto("/inventory");
  await expect(page).toHaveURL(/\/volunteer$/);
  await expect(page.getByRole("heading", { name: "Your assigned audits" })).toBeVisible();
  expect(inventoryRequests).toEqual([]);
  expect(
    await page.evaluate(
      () =>
        (window as unknown as { invitationHashesAtRequest: string[] }).invitationHashesAtRequest,
    ),
  ).not.toContain(`#token=${token}`);
  await page.getByRole("link", { name: "Open audit", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Container audit: Assigned audit case" }),
  ).toBeVisible();
});

test("early event invitations show waiting state then discover assigned return audits", async ({
  page,
}) => {
  const state = await volunteerBackend(page, true);
  state.tasksReady = false;
  await page.goto("/volunteer");
  await expect(page.getByText(/Waiting for event returns/)).toBeVisible();
  state.tasksReady = true;
  await page.getByRole("button", { name: "Refresh assigned tasks" }).click();
  await expect(page.getByRole("link", { name: "Open audit" })).toBeVisible();
});

test("a volunteer manual scan survives a brief outage and drains on reconnect", async ({
  page,
}) => {
  const state = await volunteerBackend(page, true);
  await page.goto(`/audits/tasks/${taskId}`);
  await expect(
    page.getByRole("heading", { name: "Container audit: Assigned audit case" }),
  ).toBeVisible();
  await expect(page.getByText(/saved on this device/)).toBeVisible();
  state.offline = true;
  await page.getByLabel("Scan or enter item code").fill("7K3MXY");
  await page.getByRole("button", { name: "Record scan" }).click();
  await expect(
    page.getByText(/pending work|pending command|queued|awaiting sync/i).first(),
  ).toBeVisible();
  expect(state.scanRequests).toBe(0);
  state.offline = false;
  await page.evaluate(() => window.dispatchEvent(new Event("online")));
  await expect.poll(() => state.scanRequests).toBe(1);
  await expect(page.getByText(/Assigned case - 7K3MXY/)).toBeVisible();
});

test("foreground verification ends revoked volunteer access", async ({ page }) => {
  const state = await volunteerBackend(page, true);
  await page.goto("/volunteer");
  await expect(page.getByRole("heading", { name: "Your assigned audits" })).toBeVisible();
  state.revoked = true;
  await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
  await expect(page).toHaveURL(/\/sign-in/);
  await expect(page.getByRole("heading", { name: "Sign in", exact: true })).toBeVisible();
});

test("expired volunteer identity cannot recover cached audit work while offline", async ({
  page,
}) => {
  await page.clock.install();
  const state = await volunteerBackend(page, true);
  state.expiresAt = new Date(Date.now() + 5000).toISOString();
  await page.goto(`/audits/tasks/${taskId}`);
  await expect(page.getByText(/saved on this device/)).toBeVisible();
  state.offline = true;
  await page.clock.fastForward(6000);
  await page.reload();
  await expect(page).toHaveURL(/\/sign-in/);
  await expect(page.getByRole("heading", { name: "Sign in", exact: true })).toBeVisible();
  expect(state.scanRequests).toBe(0);
});

test("an Owner issues a batch QR invitation once and can revoke it", async ({ page, baseURL }) => {
  await volunteerBackend(page, true);
  const invitation = {
    id: invitationId,
    organizationId: principal.organizationId,
    auditBatchId: batchId,
    issuedAt: new Date().toISOString(),
    expiresAt: principal.temporaryAccess.expiresAt,
  };
  let issued = false;
  let revoked = false;
  await page.route("**/api/v1/session", (route) =>
    route.fulfill({ json: { ...principal, role: "OWNER", temporaryAccess: null } }),
  );
  await page.route("**/api/v1/temporary-access/invitations**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/revoke")) {
      revoked = true;
      await route.fulfill({ status: 204 });
    } else if (route.request().method() === "POST") {
      const body = route.request().postDataJSON() as { auditBatchId: string; joinUrl: string };
      expect(body.auditBatchId).toBe(batchId);
      expect(body.joinUrl).toBe(`${baseURL}/join`);
      issued = true;
      await route.fulfill({
        status: 201,
        json: {
          invitation,
          token,
          joinUrl: `${baseURL}/join#token=${token}`,
          qrCodeDataUrl:
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jKzQAAAAASUVORK5CYII=",
        },
      });
    } else {
      await route.fulfill({
        json: issued
          ? [{ ...invitation, revokedAt: revoked ? new Date().toISOString() : null }]
          : [],
      });
    }
  });
  await page.goto(`/audits/tasks/${taskId}`);
  await page.getByRole("button", { name: "Create volunteer invitation" }).click();
  await expect(page.getByRole("dialog", { name: "Share volunteer invitation" })).toBeVisible();
  await expect(page.getByRole("img", { name: "Volunteer invitation QR code" })).toBeVisible();
  await expect(page.getByLabel("Invitation link")).toHaveValue(`${baseURL}/join#token=${token}`);
  await page.getByRole("button", { name: "Done", exact: true }).click();
  await expect(page.getByLabel("Invitation link")).toBeHidden();
  await page.getByRole("button", { name: "Revoke invitation", exact: true }).click();
  await expect.poll(() => revoked).toBe(true);
  await expect(page.getByText(/Revoked/)).toBeVisible();
});
