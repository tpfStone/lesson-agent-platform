import { test, expect, type Page } from "@playwright/test";

test("manual editing creates a version; a stale tab keeps its draft on conflict", async ({
  context,
}) => {
  let head = "V0";
  const versions = [
    {
      id: "V0",
      versionNumber: 0,
      content: "教学过程：原稿",
      sourceModule: "IMPORT_DOCX",
      createdAt: "2026-10-16T00:00:00Z",
    },
  ];
  const lesson = () => ({
    id: "lesson",
    title: "编辑验收",
    currentVersionId: head,
    updatedAt: "2026-10-16T00:00:00Z",
  });
  await context.route(/http:\/\/127\.0\.0\.1:4173\/api\//, async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/auth/me"))
      return route.fulfill({
        json: {
          id: "user",
          username: "user",
          displayName: "教师",
          role: "USER",
        },
      });
    if (path.endsWith("/auth/csrf"))
      return route.fulfill({ json: { headerName: "X-CSRF", token: "test" } });
    if (path.endsWith("/source-document"))
      return route.fulfill({ status: 404, json: {} });
    if (path.endsWith("/lessons")) return route.fulfill({ json: [lesson()] });
    if (path.endsWith("/lessons/lesson"))
      return route.fulfill({ json: { lesson: lesson(), versions } });
    if (path.endsWith("/versions") && route.request().method() === "POST") {
      const body = route.request().postDataJSON();
      if (body.expectedVersionId !== head)
        return route.fulfill({
          status: 409,
          json: { detail: "已有更新版本，请重新加载" },
        });
      head = `V${versions.length}`;
      versions.push({
        id: head,
        versionNumber: versions.length,
        content: body.content,
        sourceModule: "MANUAL",
        createdAt: "2026-10-16T01:00:00Z",
      });
      return route.fulfill({
        status: 201,
        json: { lesson: lesson(), versions },
      });
    }
    if (path.endsWith("/document")) {
      const version = versions.find((v) =>
        path.includes(`/versions/${v.id}/`),
      )!;
      return route.fulfill({
        json: {
          lessonId: "lesson",
          versionId: version.id,
          bindingStatus: "VERIFIED",
          warnings: [],
          document: {
            schemaVersion: "1.1",
            blocks: [{ id: "p", type: "paragraph", text: version.content }],
          },
        },
      });
    }
    return route.fulfill({ json: { items: [], pdf: false } });
  });
  const a = await context.newPage(),
    b = await context.newPage();
  for (const page of [a, b]) {
    await page.goto("/lessons/lesson?version=V0");
    await page.getByRole("button", { name: "编辑此版本", exact: true }).click();
    await expect(page.getByRole("dialog")).toContainText("表格");
  }
  async function save(page: Page, content: string) {
    await page
      .getByRole("textbox", { name: "教案正文", exact: true })
      .fill(content);
    await page
      .getByRole("button", { name: "保存为新版本", exact: true })
      .click();
  }
  await save(a, "教学过程：第一次修改");
  await expect(a).toHaveURL(/version=V1$/);
  await expect(a.getByRole("dialog")).toHaveCount(0);
  await a.reload();
  await expect(
    a.getByText("教学过程：第一次修改", { exact: true }),
  ).toBeVisible();
  await expect(
    a.getByRole("link", { name: "下载 DOCX", exact: true }),
  ).toHaveAttribute("href", /\/V1\/export\/docx$/);
  await save(b, "教学过程：第二页未保存草稿");
  await expect(b.getByRole("dialog").getByRole("alert")).toContainText("版本");
  await expect(
    b.getByRole("textbox", { name: "教案正文", exact: true }),
  ).toHaveValue("教学过程：第二页未保存草稿");
  expect(versions).toHaveLength(2);
  await b.getByRole("button", { name: "加载最新版本", exact: true }).click();
  await expect(b).toHaveURL(/version=V1$/);
  await expect(
    b.getByRole("textbox", { name: "教案正文", exact: true }),
  ).toHaveValue("教学过程：第二页未保存草稿");
  await b.getByRole("button", { name: "保存为新版本", exact: true }).click();
  await expect(b).toHaveURL(/version=V2$/);
});
