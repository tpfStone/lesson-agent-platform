import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { ElButton } from "element-plus";
import { createPinia } from "pinia";
import NavigationWorkbenchView from "../src/views/NavigationWorkbenchView.vue";
import { request, type NavigationState } from "../src/features/navigation/api";

vi.mock("../src/features/navigation/api", () => ({ request: vi.fn() }));
vi.mock("../src/features/navigation/bridge", async (original) => ({
  ...(await original<typeof import("../src/features/navigation/bridge")>()),
  bridgeRequest: vi.fn(async () => ({})),
}));

const wrappers: VueWrapper[] = [];
beforeEach(() => {
  vi.resetAllMocks();
  localStorage.clear();
});
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()));

function reviewState(): NavigationState {
  return {
    session: {
      id: "lookback",
      status: "ACTIVE",
      current_section_id: "homework",
      lesson_metadata: {
        subject: "数学",
        grade: "五年级",
        topic: "三角形面积",
      },
      config_snapshot: { provider: "mock", max_rounds: 5 },
      updated_at: "2026-10-18T00:00:00Z",
    },
    round: { id: "round", round_number: 1 },
    sections: [
      {
        id: "process",
        order_index: 0,
        title: "一、教学过程",
        current_content: "旧投影不应覆盖结构化修订稿",
        source_ref: { blockIds: ["heading", "table"] },
        version: { id: "process-v1", version_number: 1 },
        review: { generated_at: "now", completed_at: "now" },
        suggestions: [
          {
            id: "accepted",
            issue: "加入同伴解释",
            reason: "帮助理解推导",
            pedagogical_basis: "同伴互评",
            revision: "让同伴说明面积公式",
            revision_mode: "replace",
            target_text: "教师解释",
            decision: { id: "accept-decision", decision: "ACCEPT" },
          },
          {
            id: "rejected",
            issue: "增加课后练习",
            reason: "巩固理解",
            pedagogical_basis: "迁移应用",
            revision: "新增三题",
            revision_mode: "append",
            decision: { id: "reject-decision", decision: "REJECT" },
          },
        ],
      },
      {
        id: "homework",
        order_index: 1,
        title: "二、作业设计",
        current_content: "测量三角形面积",
        source_ref: { blockIds: ["homework-heading", "homework-text"] },
        version: { id: "homework-v0", version_number: 0 },
        review: { generated_at: null, completed_at: null },
        suggestions: [],
      },
    ],
    lesson_plan: {
      current_content: "完整修订教案",
      structured_content: {
        schemaVersion: "1.1",
        blocks: [
          { id: "heading", type: "heading", level: 1, text: "一、教学过程" },
          {
            id: "table",
            type: "table",
            tableRows: [
              ["教师活动", "学生活动"],
              ["邀请同伴解释\n追问推导过程", "说明面积公式"],
            ],
          },
          {
            id: "homework-heading",
            type: "heading",
            level: 1,
            text: "二、作业设计",
          },
          { id: "homework-text", type: "paragraph", text: "测量三角形面积" },
        ],
      },
    },
    lesson_plan_versions: [],
  };
}

async function openWorkbench(state = reviewState()) {
  const mutations: string[] = [];
  vi.mocked(request).mockImplementation(async (path, body) => {
    if (path.endsWith("/view")) return undefined;
    if (body !== undefined) mutations.push(path);
    return structuredClone(state);
  });
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/platform/navigation", component: NavigationWorkbenchView },
    ],
  });
  await router.push("/platform/navigation?session=lookback");
  await router.isReady();
  const wrapper = mount(NavigationWorkbenchView, {
    global: { plugins: [router, createPinia()] },
  });
  wrappers.push(wrapper);
  await flushPromises();
  const button = (name: string) =>
    wrapper.findAll("button").find((item) => item.text() === name);
  const click = async (name: string) => {
    expect(button(name), `button ${name}`).toBeDefined();
    await button(name)!.trigger("click");
    await flushPromises();
  };
  return { wrapper, router, mutations, button, click };
}

it("shows saved decisions and current structured text in read-only lookback without moving progress", async () => {
  const state = reviewState();
  const { wrapper, mutations, button, click } = await openWorkbench(state);
  const staleGenerate = wrapper
    .findAllComponents(ElButton)
    .find((item) => item.text() === "生成共析建议")!;
  await click("回看上一单元");
  expect(wrapper.get(".lesson-section h2").text()).toBe("一、教学过程");
  expect(wrapper.get(".lesson-section").text()).toContain(
    "邀请同伴解释\n追问推导过程",
  );
  expect(wrapper.get(".review-main").text()).toContain("已完成 · 回看中");
  expect(wrapper.get(".feedback-area").text()).toContain("加入同伴解释");
  expect(wrapper.get(".feedback-area").text()).toContain("已采纳");
  expect(wrapper.get(".feedback-area").text()).toContain("已保留原稿");
  for (const label of [
    "生成共析建议",
    "采纳建议",
    "保留原稿",
    "进入下一单元",
    "完成本轮",
    "回看上一单元",
  ]) {
    expect(button(label), label).toBeUndefined();
  }
  // Even an already captured generation handler cannot write after navigation.
  staleGenerate.vm.$emit("click");
  await flushPromises();
  expect(mutations).toEqual([]);
  expect(state.session.current_section_id).toBe("homework");
  expect(wrapper.get(".session-stats").text()).toContain("1/2");
  expect(request).toHaveBeenCalledWith("/sessions/lookback/view", {
    round_id: "round",
    section_id: "process",
    section_version_id: "process-v1",
    suggestion_ids: ["accepted", "rejected"],
    decision_ids: ["accept-decision", "reject-decision"],
  });
  await click("返回当前单元");
  expect(wrapper.get(".lesson-section h2").text()).toBe("二、作业设计");
  await click("生成共析建议");
  expect(request).toHaveBeenCalledWith("/sessions/lookback/suggestions", {
    round_id: "round",
    section_id: "homework",
  });
});

it("opens completed chapter navigation and resets to the server pending unit on refresh and session change", async () => {
  const { wrapper, router, click } = await openWorkbench();
  const completedButton = wrapper.find(".section-nav li.completed button");
  expect(completedButton.exists()).toBe(true);
  await completedButton.trigger("click");
  await flushPromises();
  expect(wrapper.get(".lesson-section h2").text()).toBe("一、教学过程");
  await click("刷新当前进度");
  expect(wrapper.get(".lesson-section h2").text()).toBe("二、作业设计");
  await click("回看上一单元");
  const other = reviewState();
  other.session.id = "other";
  other.sections[1]!.title = "另一会话的当前单元";
  vi.mocked(request).mockImplementation(async (path) =>
    path.endsWith("/view") ? undefined : structuredClone(other),
  );
  await router.push("/platform/navigation?session=other");
  await flushPromises();
  expect(wrapper.get(".lesson-section h2").text()).toBe("另一会话的当前单元");
  expect(wrapper.text()).not.toContain("已完成 · 回看中");
});

it("has no back action on the first pending unit and disables future chapter navigation", async () => {
  const state = reviewState();
  state.session.current_section_id = "process";
  state.sections[0]!.review = { generated_at: null, completed_at: null };
  state.sections[0]!.suggestions = [];
  const { wrapper, button } = await openWorkbench(state);
  expect(button("回看上一单元")).toBeUndefined();
  expect(
    wrapper.findAll(".section-nav li")[1]!.get("button").attributes("disabled"),
  ).toBeDefined();
});

it("finishes a session switch during recovery without restoring the previous session or its lookback", async () => {
  const { wrapper, router, click } = await openWorkbench();
  await click("回看上一单元");
  let resolveOld!: (value: NavigationState) => void;
  const other = reviewState();
  other.session.id = "other";
  other.sections[1]!.title = "另一会话的当前单元";
  vi.mocked(request).mockImplementation(async (path) => {
    if (path.endsWith("/view")) return undefined;
    if (path.includes("/other/")) return structuredClone(other);
    return new Promise<NavigationState>((resolve) => {
      resolveOld = resolve;
    });
  });
  await click("刷新当前进度");
  await router.push("/platform/navigation?session=other");
  resolveOld(reviewState());
  await flushPromises();
  expect(router.currentRoute.value.query.session).toBe("other");
  expect(wrapper.get(".lesson-section h2").text()).toBe("另一会话的当前单元");
  expect(wrapper.text()).not.toContain("已完成 · 回看中");
});

it("keeps undecided legacy suggestions read-only while looking back", async () => {
  const state = reviewState();
  state.sections[0]!.suggestions[0]!.decision = null;
  const { wrapper, mutations, click } = await openWorkbench(state);
  await click("回看上一单元");
  expect(wrapper.find(".decision-actions").exists()).toBe(false);
  expect(wrapper.find(".next-bar").exists()).toBe(false);
  expect(mutations).toEqual([]);
});

it("keeps a failed session switch reviewable without retrying automatically or sending writes to the old session", async () => {
  const { wrapper, router, mutations, click } = await openWorkbench();
  const other = reviewState();
  other.session.id = "other";
  let loads = 0;
  vi.mocked(request).mockImplementation(async (path, body) => {
    if (path.endsWith("/view")) return undefined;
    if (body !== undefined) mutations.push(path);
    if (path === "/sessions/other/current-state" && ++loads === 1)
      throw new Error("会话暂时无法恢复");
    return structuredClone(other);
  });
  await router.push("/platform/navigation?session=other");
  await flushPromises();
  expect(loads).toBe(1);
  expect(wrapper.text()).toContain("会话暂时无法恢复");
  await click("生成共析建议");
  expect(mutations).toEqual([]);
  await click("刷新当前进度");
  expect(loads).toBe(2);
  expect(router.currentRoute.value.query.session).toBe("other");
});

it("allows revisiting a session after its earlier recovery failed", async () => {
  const { router } = await openWorkbench();
  let loads = 0;
  vi.mocked(request).mockImplementation(async (path) => {
    if (path.endsWith("/view")) return undefined;
    if (path.includes("/other/")) {
      loads++;
      throw new Error("暂时无法恢复");
    }
    return reviewState();
  });
  await router.push("/platform/navigation?session=other");
  await flushPromises();
  expect(loads).toBe(1);
  await router.push("/platform/navigation?session=lookback");
  await router.push("/platform/navigation?session=other");
  await flushPromises();
  expect(loads).toBe(2);
});

it("opens a completed round for lookback and hides all round mutations until returning", async () => {
  const state = reviewState();
  state.session.status = "ROUND_COMPLETED";
  state.session.current_section_id = null;
  state.sections[1]!.review = { generated_at: "now", completed_at: "now" };
  const { wrapper, mutations, button, click } = await openWorkbench(state);
  await click("回看已完成单元");
  expect(wrapper.get(".lesson-section h2").text()).toBe("二、作业设计");
  expect(button("继续下一轮")).toBeUndefined();
  expect(button("结束本次共析")).toBeUndefined();
  await click("回看上一单元");
  expect(wrapper.get(".lesson-section h2").text()).toBe("一、教学过程");
  await click("返回本轮结果");
  expect(button("继续下一轮")).toBeDefined();
  expect(mutations).toEqual([]);
});

it("opens terminated sessions for read-only lookback and returns to final results", async () => {
  const state = reviewState();
  state.session.status = "TERMINATED";
  state.session.current_section_id = null;
  state.sections[1]!.review = { generated_at: "now", completed_at: "now" };
  const { wrapper, router, mutations, button, click } =
    await openWorkbench(state);
  expect(wrapper.get(".final-panel h2").text()).toBe("最终修订教案");
  const staleNewSession = wrapper
    .findAllComponents(ElButton)
    .find((item) => item.text() === "开始新的协同共析")!;
  await click("回看已完成单元");
  expect(wrapper.get(".lesson-section h2").text()).toBe("二、作业设计");
  expect(wrapper.get(".review-main").text()).toContain("已完成 · 回看中");
  expect(wrapper.find(".transfer-panel").exists()).toBe(false);
  for (const label of [
    "继续下一轮",
    "结束本次共析",
    "生成共析建议",
    "完成本轮",
    "开始新的协同共析",
  ])
    expect(button(label)).toBeUndefined();
  staleNewSession.vm.$emit("click");
  await flushPromises();
  expect(router.currentRoute.value.fullPath).toBe(
    "/platform/navigation?session=lookback",
  );
  await click("回看上一单元");
  expect(wrapper.get(".feedback-area").text()).toContain("已采纳");
  expect(wrapper.get(".feedback-area").text()).toContain("已保留原稿");
  await click("返回最终结果");
  expect(wrapper.get(".final-panel h2").text()).toBe("最终修订教案");
  expect(button("开始新的协同共析")).toBeDefined();
  expect(mutations).toEqual([]);
  expect(state.session.status).toBe("TERMINATED");
});

it.each([false, true, undefined])(
  "enforces candidate append capability %s while retaining legacy behavior",
  async (canAppend) => {
    const state = reviewState();
    state.sections[1]!.review.generated_at = "now";
    state.sections[1]!.suggestions = [
      { ...state.sections[0]!.suggestions[0]!, id: "unsafe", decision: null },
    ];
    state.revision_candidate = {
      suggestion_id: "unsafe",
      reason: "原表格位置已变化，无法应用修改。",
      candidate: "修改后的内容",
      expected_version_id: "homework-v0",
      ...(canAppend === undefined ? {} : { can_append: canAppend }),
    };
    const { wrapper, button, click } = await openWorkbench(state);
    expect(wrapper.get(".candidate-box").text()).toContain("原表格位置已变化");
    if (canAppend === false) {
      expect(button("作为补充采纳（保留原文）")).toBeUndefined();
      expect(button("采纳建议")).toBeUndefined();
      expect(wrapper.get(".candidate-box").text()).toContain("无法应用此修改");
      await click("保留原稿");
      expect(request).toHaveBeenCalledWith(
        "/sessions/lookback/suggestions/unsafe/decision",
        { decision: "REJECT" },
      );
    } else {
      await click("作为补充采纳（保留原文）");
      expect(request).toHaveBeenCalledWith(
        "/sessions/lookback/suggestions/unsafe/decision",
        {
          decision: "ACCEPT",
          confirm_append: true,
          expected_version_id: "homework-v0",
        },
      );
    }
  },
);
