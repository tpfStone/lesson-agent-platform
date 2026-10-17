import { expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import NavigationWorkbenchView from "../src/views/NavigationWorkbenchView.vue";
import { request, type NavigationState } from "../src/features/navigation/api";

vi.mock("../src/features/navigation/api", () => ({ request: vi.fn() }));

it("shows basic information outside chapter progress and advances the entire table at once", async () => {
  const state: NavigationState = {
    session: {
      id: "session",
      status: "ACTIVE",
      current_section_id: "process",
      updated_at: "now",
      lesson_metadata: { subject: "数学", grade: "五年级", topic: "面积" },
      config_snapshot: { provider: "mock", max_rounds: 5 },
    },
    round: { id: "round", round_number: 1 },
    sections: [
      {
        id: "process",
        order_index: 0,
        title: "一、教学过程",
        current_content: "教学过程完整正文",
        source_ref: { blockIds: ["h1", "p", "table"] },
        version: { id: "v0", version_number: 0 },
        review: { generated_at: null, completed_at: null },
        suggestions: [],
      },
      {
        id: "homework",
        order_index: 1,
        title: "二、作业设计",
        current_content: "测量面积",
        source_ref: { blockIds: ["h2", "work"] },
        version: { id: "v1", version_number: 0 },
        review: { generated_at: null, completed_at: null },
        suggestions: [],
      },
    ],
    lesson_plan: {
      current_content: "完整教案",
      structured_content: {
        schemaVersion: "1.1",
        blocks: [
          { id: "intro", type: "paragraph", text: "五年级 · 40分钟" },
          { id: "h1", type: "heading", level: 1, text: "一、教学过程" },
          { id: "p", type: "paragraph", text: "先观察图形" },
          {
            id: "table",
            type: "table",
            tableRows: [
              ["教师", "学生"],
              ["导入提问", "观察"],
              ["引导探究", "解释"],
            ],
          },
          { id: "h2", type: "heading", level: 1, text: "二、作业设计" },
          { id: "work", type: "paragraph", text: "测量面积" },
        ],
      },
    },
    lesson_plan_versions: [],
  };
  vi.mocked(request).mockImplementation(async (path, body) => {
    if (path.endsWith("/view")) return undefined;
    if (path.endsWith("/suggestions")) {
      expect(body).toEqual({ round_id: "round", section_id: "process" });
      state.sections[0]!.review.generated_at = "now";
    } else if (path.endsWith("/complete-section")) {
      expect(body).toEqual({ round_id: "round", section_id: "process" });
      state.sections[0]!.review.completed_at = "now";
      state.session.current_section_id = "homework";
    } else expect(path).toBe("/sessions/session/current-state");
    return structuredClone(state);
  });
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/platform/navigation", component: NavigationWorkbenchView },
    ],
  });
  await router.push("/platform/navigation?session=session");
  await router.isReady();
  const wrapper = mount(NavigationWorkbenchView, {
    global: { plugins: [router] },
  });
  await flushPromises();
  expect(wrapper.get(".lesson-overview").text()).toContain("五年级 · 40分钟");
  expect(wrapper.findAll(".section-nav li")).toHaveLength(2);
  expect(wrapper.get(".section-nav").text()).not.toContain("40分钟");
  expect(wrapper.findAll(".lesson-section tr")).toHaveLength(3);
  expect(wrapper.get(".lesson-section").text()).toContain("先观察图形");
  const next = () =>
    wrapper
      .findAll("button")
      .find((button) => button.text() === "进入下一单元")!;
  expect(next().attributes("disabled")).toBeDefined();
  await wrapper
    .findAll("button")
    .find((button) => button.text() === "生成共析建议")!
    .trigger("click");
  await flushPromises();
  expect(next().attributes("disabled")).toBeUndefined();
  await next().trigger("click");
  await flushPromises();
  expect(wrapper.get(".session-stats").text()).toContain("1/2");
  expect(wrapper.get(".lesson-section").text()).toContain("测量面积");
  expect(wrapper.findAll(".lesson-section tr")).toHaveLength(0);
});
