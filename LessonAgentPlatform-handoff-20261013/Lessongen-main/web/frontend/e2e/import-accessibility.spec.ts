import { test, expect, type Page } from "@playwright/test";

// The HTTP boundary is controlled here; real import/storage is exercised by Java/API acceptance.
async function prepare(page: Page) {
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
        json: { headerName: "X-XSRF-TOKEN", token: "test" },
      });
    if (path.endsWith("/imports/docx/preview"))
      return route.fulfill({
        json: {
          originalFilename: "长标题".repeat(40) + ".docx",
          sizeBytes: 100,
          extractedContent: "教学过程\n备注01",
          contentLength: 11,
          warnings: [],
          structuredContent: {
            schemaVersion: "1.1",
            blocks: [
              { id: "p", type: "paragraph", text: "长段落".repeat(60) },
              {
                id: "t",
                type: "table",
                tableRows: [
                  ["环节", "教师活动", "备注"],
                  ["导入", "提问", "备注01"],
                ],
              },
            ],
          },
        },
      });
    return route.fulfill({ json: [] });
  });
}

test.beforeEach(async ({ page }) => prepare(page));

test("explicit upload remains available during a bound F1 workflow", async ({
  page,
}) => {
  const run = {
    templateId: "lesson-polish",
    templateName: "教案研磨",
    steps: [{ id: "F1", name: "评价" }],
    lessonId: "bound-lesson",
    versionId: "bound-version",
    currentIndex: 0,
    completedIndexes: [],
  };
  await page.addInitScript(
    (value) =>
      localStorage.setItem(
        "platform-active-workflow-v2",
        JSON.stringify(value),
      ),
    run,
  );
  await page.goto("/lessons");
  await page
    .getByRole("link", { name: /上传.*教案/ })
    .first()
    .click();
  await expect(
    page.getByRole("dialog", { name: "上传教案", exact: true }),
  ).toBeVisible();
  expect(
    await page.evaluate(() =>
      JSON.parse(localStorage.getItem("platform-active-workflow-v2") || "null"),
    ),
  ).toEqual(run);
});

test("empty lesson list leads directly to an upload control", async ({
  page,
}) => {
  await page.goto("/lessons");
  await page
    .getByRole("link", { name: /上传.*教案/ })
    .first()
    .click();
  await expect(
    page.getByRole("button", { name: /选择DOCX文件/ }),
  ).toBeVisible();
});

test("upload dialog contains keyboard focus and restores it on Escape", async ({
  page,
}) => {
  await page.goto("/review");
  const trigger = page.getByRole("button", { name: "上传教案", exact: true });
  await trigger.click();
  const dialog = page.getByRole("dialog", { name: "上传教案", exact: true });
  await expect(dialog).toBeVisible();
  await expect
    .poll(() => dialog.evaluate((el) => el.contains(document.activeElement)))
    .toBe(true);
  await dialog.getByRole("button", { name: "关闭", exact: true }).focus();
  await page.keyboard.press("Shift+Tab");
  await expect(
    dialog.getByRole("button", { name: /选择DOCX文件/ }),
  ).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(
    dialog.getByRole("button", { name: "关闭", exact: true }),
  ).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(trigger).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(dialog).toBeVisible();
});

test("dropping a DOCX previews it and invalid drops give a visible error", async ({
  page,
}) => {
  await page.goto("/review");
  await page.getByRole("button", { name: "上传教案", exact: true }).click();
  for (const name of ["invalid.txt", "lesson.docx"]) {
    const files = await page.evaluateHandle((filename) => {
      const data = new DataTransfer();
      data.items.add(new File(["fixture"], filename));
      return data;
    }, name);
    await page
      .locator(".upload-area")
      .dispatchEvent("drop", { dataTransfer: files });
    await files.dispose();
    if (name.endsWith(".txt"))
      await expect(page.getByRole("alert")).toContainText("DOCX");
  }
  await expect(
    page.getByRole("button", { name: "保存到我的教案", exact: true }),
  ).toBeVisible();
});

test("390px preview keeps fields and actions inside dialog while table scrolls", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/review");
  await page.getByRole("button", { name: "上传教案", exact: true }).click();
  await page.locator('input[type="file"]').setInputFiles({
    name: "lesson.docx",
    mimeType:
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    buffer: Buffer.from("fixture"),
  });
  await page.getByText("核对教案内容", { exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "上传教案", exact: true });
  const sizes = await dialog.evaluate((el) => ({
    width: el.clientWidth,
    scroll: el.scrollWidth,
  }));
  expect(sizes.scroll).toBeLessThanOrEqual(sizes.width + 1);
  await page
    .getByRole("button", { name: "保存到我的教案", exact: true })
    .scrollIntoViewIfNeeded();
  await expect(
    page.getByRole("button", { name: "保存到我的教案", exact: true }),
  ).toBeInViewport();
  await page.screenshot({
    path: "D:/projects/LessonAgentPlatform/.repair/repair-execution-20261016/import-390.png",
    fullPage: true,
  });
});
