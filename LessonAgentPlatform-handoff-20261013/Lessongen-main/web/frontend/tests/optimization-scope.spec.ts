import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { createPinia } from "pinia";
import axios from "axios";
import PlatformOptimizeView from "../src/views/PlatformOptimizeView.vue";
import OptimizationPanel from "../src/components/OptimizationPanel.vue";
import type { OptimizationSummary } from "../src/types/api";

vi.mock("axios", () => ({
  default: { get: vi.fn(), post: vi.fn(), isAxiosError: () => false },
}));
vi.mock("../src/api/client", () => ({
  makeIdempotencyKey: () => "scope-test-key",
}));
let wrapper: VueWrapper;
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  vi.mocked(axios.get).mockImplementation(async (url) => {
    if (url === "/api/platform/lessons/lesson")
      return {
        data: {
          lesson: {
            id: "lesson",
            title: "面积",
            subject: "数学",
            grade: "五年级",
            topic: "面积",
            durationMinutes: 45,
            currentVersionId: "v1",
          },
          versions: [{ id: "v1", versionNumber: 1, content: "原稿" }],
        },
      };
    return { data: [] };
  });
  vi.mocked(axios.post).mockResolvedValue({ data: { jobId: "job" } });
});
afterEach(() => wrapper?.unmount());
async function open() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/optimize", component: { template: "<div />" } },
      { path: "/jobs/:id", component: { template: "<div />" } },
    ],
  });
  await router.push("/optimize?lessonId=lesson&versionId=v1");
  await router.isReady();
  wrapper = mount(PlatformOptimizeView, {
    global: {
      plugins: [router, createPinia()],
      stubs: { LessonSelectorBar: true },
    },
  });
  await flushPromises();
}
function start() {
  return wrapper
    .findAll("button")
    .find((button) => button.text().includes("开始设计修改"))!;
}
it("submits an explicit selected field boundary alongside free-form focus", async () => {
  await open();
  const section = wrapper.find('[aria-label="本次可修改范围"]');
  expect(section.exists()).toBe(true);
  expect(section.text()).toContain("范围外的原有缺项仅作提示");
  await start().trigger("click");
  await flushPromises();
  expect(vi.mocked(axios.post).mock.calls[0]?.[1]).toMatchObject({
    optimizationScope: { mode: "local", editablePaths: ["/procedure_steps"] },
  });
});
it("does not submit an empty local scope or silently widen it to full", async () => {
  await open();
  const section = wrapper.find('[aria-label="本次可修改范围"]');
  expect(section.exists()).toBe(true);
  await section.find('input[value="/procedure_steps"]').setValue(false);
  await start().trigger("click");
  expect(axios.post).not.toHaveBeenCalled();
  expect(wrapper.text()).toContain("请至少选择一个可修改栏目");
});

it("shows the saved scope and labels outside opinions as suggestions, never edits", () => {
  const summary = {
    outcome: "reviewed_unchanged",
    content_changed: false,
    changed_sections: [],
    rounds: [],
    scope: { mode: "local", editable_paths: ["/procedure_steps"] },
    baseline_warnings: ["原稿已有且位于本次范围外，仅提示：作业为空。"],
    reviewed_issues: [
      {
        critique_id: "c1",
        role: "critic",
        target_path: "/homework",
        issue: "缺少作业",
        suggestion: "补充作业",
        status: "deferred",
        decision: "defer",
        decision_reason: "本次范围外",
      },
    ],
  } as unknown as OptimizationSummary;
  wrapper = mount(OptimizationPanel, { props: { summary } });
  expect(wrapper.text()).toContain("本次仅修改");
  expect(wrapper.text()).toContain("作业为空");
  expect(wrapper.text()).toContain("仅保留建议");
  expect(wrapper.find(".issue-state").text()).toBe("仅保留建议");
});

it("renders existing missing-section warnings with Chinese names without changing saved evidence", () => {
  const summary = {
    outcome: "reviewed_unchanged",
    content_changed: false,
    changed_sections: [],
    rounds: [],
    scope: { mode: "local", editable_paths: ["/procedure_steps"] },
    baseline_warnings: [
      "原稿已有且位于本次范围外，仅提示：任务要求的章节 content_analysis 为空。",
      "原稿已有且位于本次范围外，仅提示：任务要求的章节 key_points 为空。",
      "任务要求的章节 homework 为空。",
    ],
  } as unknown as OptimizationSummary;
  const savedEvidence = JSON.stringify(summary);
  wrapper = mount(OptimizationPanel, { props: { summary } });
  const warnings = wrapper.get('[aria-label="本次优化范围"]').findAll("li");
  expect(warnings.map((warning) => warning.text())).toEqual([
    "原稿已有且位于本次范围外，仅提示：任务要求的章节“内容分析”为空。",
    "原稿已有且位于本次范围外，仅提示：任务要求的章节“教学重点”为空。",
    "任务要求的章节“作业设计”为空。",
  ]);
  expect(JSON.stringify(summary)).toBe(savedEvidence);
});

it("makes an unknown warning location explicit and preserves unrelated warning text", () => {
  const summary = {
    outcome: "reviewed_unchanged",
    content_changed: false,
    changed_sections: [],
    rounds: [],
    scope: { mode: "local", editable_paths: ["/procedure_steps"] },
    baseline_warnings: [
      "原稿已有且位于本次范围外，仅提示：任务要求的章节 future_section 为空。",
      "任务要求了未知章节：future_section。",
      "原稿已有且位于本次范围外，仅提示：课时合计为 40 分钟。",
      "请对照教材中的 content_analysis 示例核对原稿。",
    ],
  } as unknown as OptimizationSummary;
  wrapper = mount(OptimizationPanel, { props: { summary } });
  const warnings = wrapper.get('[aria-label="本次优化范围"]').findAll("li");
  expect(warnings.map((warning) => warning.text())).toEqual([
    "原稿已有且位于本次范围外，仅提示：任务要求的章节（位置暂无法识别，请对照原稿核对）为空。",
    "任务要求的章节位置暂无法识别，请对照原稿核对。",
    "原稿已有且位于本次范围外，仅提示：课时合计为 40 分钟。",
    "请对照教材中的 content_analysis 示例核对原稿。",
  ]);
});
