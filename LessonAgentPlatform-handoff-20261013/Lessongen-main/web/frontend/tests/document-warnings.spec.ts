import { beforeEach, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import axios from "axios";
import LessonImportPanel from "../src/components/LessonImportPanel.vue";
import VersionDocumentPreview from "../src/components/VersionDocumentPreview.vue";
import LessonSpaceView from "../src/views/LessonSpaceView.vue";
import DocumentWarnings from "../src/components/DocumentWarnings.vue";
import DocumentDownloadNotice from "../src/components/DocumentDownloadNotice.vue";
import type { VersionDocument } from "../src/types/lesson-document";

vi.mock("axios", () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    isAxiosError: (reason: { isAxiosError?: boolean }) =>
      reason?.isAxiosError === true,
  },
}));
beforeEach(() => vi.resetAllMocks());

const warnings = [
  "原稿包含图片，图片内容未提取；请保留原稿核对。",
  "原稿包含文本框，文本框内容可能不完整。",
];
const document = {
  schemaVersion: "1.1",
  blocks: [{ id: "p1", type: "paragraph", text: "标准正文" }],
};
const snapshot = (bindingStatus: VersionDocument["bindingStatus"]) => ({
  lessonId: "lesson",
  versionId: "version",
  document,
  bindingStatus,
  warnings,
  contentSha256: "a".repeat(64),
  structureSha256: "b".repeat(64),
});
async function routerAt(path = "/") {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/", component: { template: "<div />" } },
      { path: "/lessons/:lessonId", component: { template: "<div />" } },
    ],
  });
  await router.push(path);
  await router.isReady();
  return router;
}

it("shows import warnings before expanding the preview or saving", async () => {
  vi.mocked(axios.post).mockResolvedValueOnce({
    data: {
      originalFilename: "复杂教案.docx",
      sizeBytes: 100,
      extractedContent: "标准正文",
      contentLength: 4,
      structuredContent: document,
      warnings,
    },
  });
  const wrapper = mount(LessonImportPanel, {
    global: { plugins: [await routerAt()] },
  });
  const input = wrapper.get<HTMLInputElement>('input[type="file"]');
  Object.defineProperty(input.element, "files", {
    value: [new File(["docx"], "复杂教案.docx")],
  });
  await input.trigger("change");
  await flushPromises();
  expect(wrapper.get("details").attributes("open")).toBeUndefined();
  for (const warning of warnings) {
    const message = wrapper
      .findAll("li, p")
      .find((node) => node.text() === warning);
    expect(message, warning).toBeDefined();
    expect(message!.element.closest("details"), warning).toBeNull();
  }
  expect(wrapper.text()).toContain("保存后可在教案空间下载原稿核对");
});

it.each(["TEXT_FALLBACK", "UNVERIFIED"] as const)(
  "retains specific warnings with the %s compatible body",
  async (bindingStatus) => {
    vi.mocked(axios.get).mockResolvedValueOnce({
      data: snapshot(bindingStatus),
    });
    const wrapper = mount(VersionDocumentPreview, {
      props: {
        lessonId: "lesson",
        versionId: "version",
        content: "手动版本正文",
      },
    });
    await flushPromises();
    expect(wrapper.text()).toContain("手动版本正文");
    for (const warning of warnings) expect(wrapper.text()).toContain(warning);
    expect(wrapper.text()).toContain("正文兼容显示");
  },
);

it("does not invent specific loss warnings when a version has no snapshot", async () => {
  vi.mocked(axios.get).mockRejectedValueOnce({
    isAxiosError: true,
    response: { status: 404, data: { code: "LESSON_DOCUMENT_NOT_FOUND" } },
  });
  const wrapper = mount(VersionDocumentPreview, {
    props: { lessonId: "lesson", versionId: "version", content: "旧版本正文" },
  });
  await flushPromises();
  expect(wrapper.text()).toContain("旧版本正文");
  expect(wrapper.text()).toContain("正文兼容显示");
  for (const warning of warnings) expect(wrapper.text()).not.toContain(warning);
});

it("does not show a loss warning or original-file link without evidence", () => {
  const warningsPanel = mount(DocumentWarnings, { props: { warnings: [] } });
  expect(warningsPanel.find('[role="status"]').exists()).toBe(false);
  const notice = mount(DocumentDownloadNotice);
  expect(notice.text()).toContain("可能无法完整保留");
  expect(notice.text()).toContain("如有原稿");
  expect(notice.find("a").exists()).toBe(false);
});

it("links a download notice to the selected lesson space", async () => {
  const notice = mount(DocumentDownloadNotice, {
    props: { lessonId: "lesson" },
    global: { plugins: [await routerAt()] },
  });
  expect(notice.get("a").attributes("href")).toBe("/lessons/lesson");
  expect(notice.text()).toContain("下载稿采用统一排版");
});

it("shows fallback warnings beside version downloads and keeps the original accessible", async () => {
  const lesson = {
    id: "lesson",
    title: "复杂教案",
    subject: "语文",
    grade: "四年级",
    topic: "课文",
    currentVersionId: "version",
    updatedAt: "2026-10-16T10:00:00Z",
  };
  vi.mocked(axios.get).mockImplementation(async (url: string) => {
    if (url.endsWith("/document")) return { data: snapshot("TEXT_FALLBACK") };
    if (url.endsWith("/capabilities")) return { data: { pdf: true } };
    if (url.endsWith("/source-document"))
      return {
        data: {
          originalFilename: "复杂教案.docx",
          sizeBytes: 100,
          createdAt: lesson.updatedAt,
        },
      };
    if (url === "/api/platform/lessons") return { data: [lesson] };
    if (url === "/api/platform/lessons/lesson")
      return {
        data: {
          lesson,
          versions: [
            {
              id: "version",
              versionNumber: 1,
              content: "手动版本正文",
              sourceModule: "MANUAL",
              createdAt: lesson.updatedAt,
            },
          ],
        },
      };
    throw new Error(`Unexpected request ${url}`);
  });
  const wrapper = mount(LessonSpaceView, {
    global: {
      plugins: [await routerAt("/lessons/lesson")],
      stubs: { LessonIssuePanel: true },
    },
  });
  await flushPromises();
  for (const warning of warnings) expect(wrapper.text()).toContain(warning);
  expect(wrapper.text()).toContain("手动版本正文");
  expect(wrapper.text()).toContain("下载稿采用统一排版");
  expect(
    wrapper.findAll(
      'a[href="/api/platform/lessons/lesson/source-document/file"]',
    ).length,
  ).toBeGreaterThan(0);
  expect(wrapper.find('a[href$="/export/docx"]').exists()).toBe(true);
  expect(wrapper.find('a[href$="/export/pdf"]').exists()).toBe(true);
});
