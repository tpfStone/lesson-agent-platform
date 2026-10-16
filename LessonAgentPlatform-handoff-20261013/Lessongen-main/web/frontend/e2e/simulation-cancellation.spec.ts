import { test, expect, type Page } from "@playwright/test";

// Real browser and confirmation dialog; the entire HTTP boundary is local and controlled.
async function prepare(
  page: Page,
  remoteSession: boolean,
  unavailable: boolean,
) {
  let state = remoteSession ? "RUNNING" : "QUEUED";
  let ends = 0;
  let statusReads = 0;
  await page.route(/http:\/\/127\.0\.0\.1:4173\/api\//, async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/auth/me"))
      return route.fulfill({
        json: {
          id: "teacher",
          username: "teacher",
          displayName: "教师",
          role: "USER",
        },
      });
    if (path.endsWith("/auth/csrf"))
      return route.fulfill({
        json: { headerName: "X-CSRF", token: "local-test" },
      });
    if (path === "/api/platform/lessons")
      return route.fulfill({
        json: [{ id: "lesson", title: "受控取消课堂", currentVersionId: "v0" }],
      });
    if (path === "/api/platform/lessons/lesson")
      return route.fulfill({
        json: { versions: [{ id: "v0", versionNumber: 0 }] },
      });
    if (path.endsWith("/simulations")) return route.fulfill({ json: [] });
    if (path === "/api/platform/simulation-launches/launch/end") {
      expect(route.request().method()).toBe("POST");
      ends++;
      state = unavailable ? "STOPPING" : "INTERRUPTED";
      return route.fulfill({
        status: unavailable ? 502 : 200,
        json: unavailable
          ? { detail: "远端结束尚未确认，系统会继续重试" }
          : { status: "INTERRUPTED" },
      });
    }
    if (path.endsWith("/classroom"))
      return route.fulfill({
        json: {
          status: "RUNNING",
          activeRoles: ["teacher"],
          limits: { timeoutSeconds: 15 },
        },
      });
    if (path.endsWith("/events"))
      return route.fulfill({ json: { events: [] } });
    if (path === "/api/platform/simulation-launches/launch") {
      statusReads++;
      return route.fulfill({
        json: {
          launchId: "launch",
          state,
          f3SessionId: remoteSession ? "remote" : null,
          message: "受控课堂状态",
          simulationRunId: null,
          lessonId: "lesson",
          versionId: "v0",
        },
      });
    }
    return route.fulfill({ status: 404, json: {} });
  });
  await page.goto("/simulate?lessonId=lesson&versionId=v0&launchId=launch");
  await expect(page.locator(".live-section")).toBeVisible();
  return { ends: () => ends, statusReads: () => statusReads };
}

async function endThroughDialog(page: Page) {
  await page.getByRole("button", { name: "结束课堂", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "提前结束本次课堂？" });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "结束课堂", exact: true }).click();
}

test("a preparing classroom can be cancelled through the real confirmation dialog", async ({
  page,
}) => {
  const requests = await prepare(page, false, false);
  await expect(page.locator(".live-title")).toContainText("准备中");
  await endThroughDialog(page);
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.locator(".live-section")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "开始课堂推演", exact: true }),
  ).toBeVisible();
  expect(requests.ends()).toBe(1);
});

test("a remote end failure preserves its warning and closes confirmation after durable stopping", async ({
  page,
}) => {
  const requests = await prepare(page, true, true);
  await expect(page.locator(".live-title")).toContainText("进行中");
  await endThroughDialog(page);
  await expect(page.locator(".live-title")).toContainText("正在结束");
  await expect(page.getByRole("alert")).toContainText("远端结束尚未确认");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "暂停课堂", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "继续课堂", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("textbox", { name: "向课堂发送内容" }),
  ).toBeDisabled();
  await expect.poll(requests.statusReads).toBeGreaterThanOrEqual(3);
  await expect(page.getByRole("alert")).toContainText("远端结束尚未确认");
  expect(requests.ends()).toBe(1);
});
