import { test, expect } from "@playwright/test";
test("browser history selects the requested version and its matching download", async ({
  page,
}) => {
  page.on("pageerror", (error) =>
    console.error("Browser error:", error.message),
  );
  await page.route(/http:\/\/127\.0\.0\.1:4173\/api\//, async (route) => {
    const path = new URL(route.request().url()).pathname;
    const lesson = {
      id: "lesson",
      title: "面积教案",
      subject: "数学",
      grade: "五年级",
      topic: "面积",
      currentVersionId: "B",
      updatedAt: "2026-10-14T00:00:00Z",
    };
    if (path.endsWith("/auth/csrf"))
      return route.fulfill({
        json: { headerName: "X-XSRF-TOKEN", token: "test" },
      });
    if (path.endsWith("/auth/me"))
      return route.fulfill({
        json: {
          id: "teacher",
          username: "teacher",
          displayName: "教师",
          role: "USER",
        },
      });
    if (path.endsWith("/source-document"))
      return route.fulfill({
        status: 404,
        json: { code: "SOURCE_DOCUMENT_NOT_FOUND" },
      });
    if (path.endsWith("/document")) {
      const versionId = path.includes("/A/") ? "A" : "B";
      return route.fulfill({
        json: {
          lessonId: "lesson",
          versionId,
          bindingStatus: "VERIFIED",
          warnings: [],
          document: {
            schemaVersion: "1.1",
            blocks: [
              {
                id: "b",
                type: "paragraph",
                text: versionId === "A" ? "初始版本内容" : "修改版本内容",
              },
            ],
          },
        },
      });
    }
    if (path.endsWith("/lessons/lesson"))
      return route.fulfill({
        json: {
          lesson,
          versions: ["A", "B"].map((id, i) => ({
            id,
            versionNumber: i,
            content: id,
            sourceModule: "MANUAL",
            createdAt: "2026-10-14T00:00:00Z",
          })),
        },
      });
    if (path.endsWith("/lessons")) return route.fulfill({ json: [lesson] });
    if (path.endsWith("/issues")) return route.fulfill({ json: { items: [] } });
    return route.fulfill({ json: [] });
  });
  await page.goto("/lessons/lesson?version=A");
  await expect(page.getByText("初始版本内容", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: /^V1/ }).click();
  await expect(page).toHaveURL(/version=B$/);
  await expect(page.getByText("修改版本内容", { exact: true })).toBeVisible();
  await page.goBack();
  await expect(page.getByText("初始版本内容", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "下载 DOCX" })).toHaveAttribute(
    "href",
    /\/versions\/A\/export\/docx$/,
  );
});
