import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { createPinia } from "pinia";
import axios from "axios";
import SimulationView from "../src/views/SimulationView.vue";

vi.mock("axios", () => ({
  default: { get: vi.fn(), post: vi.fn(), isAxiosError: () => true },
}));
vi.mock("../src/api/client", () => ({ makeIdempotencyKey: () => "test-key" }));
let wrapper: VueWrapper;
let state: string;
let session: string | null;
let archived: Record<string, unknown> | null;
beforeEach(() => {
  vi.resetAllMocks();
  state = "QUEUED";
  session = null;
  archived = null;
  vi.mocked(axios.get).mockImplementation(async (url) => {
    if (url === "/api/platform/lessons")
      return {
        data: [{ id: "lesson", title: "面积", currentVersionId: "v0" }],
      };
    if (
      url === "/api/platform/lessons/lesson" ||
      url === "/api/platform/lessons/other"
    )
      return { data: { versions: [{ id: "v0", versionNumber: 0 }] } };
    if (url.endsWith("/simulations")) return { data: [] };
    if (url.endsWith("/simulations/run")) return { data: archived };
    if (url.endsWith("/classroom")) return { data: { status: "RUNNING" } };
    if (url.endsWith("/events")) return { data: { events: [] } };
    return {
      data: {
        launchId: "launch",
        state,
        f3SessionId: session,
        message: "测试课堂",
        simulationRunId: archived ? "run" : null,
      },
    };
  });
});
afterEach(() => wrapper?.unmount());
async function open() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/simulate", component: { template: "<div />" } }],
  });
  await router.push("/simulate?lessonId=lesson&versionId=v0&launchId=launch");
  await router.isReady();
  wrapper = mount(SimulationView, {
    global: {
      plugins: [router, createPinia()],
      stubs: {
        ConfirmDialog: {
          props: ["open"],
          emits: ["confirm"],
          template:
            '<button v-if="open" data-test="confirm-end" @click="$emit(\'confirm\')">确认结束</button>',
        },
      },
    },
  });
  await flushPromises();
  return router;
}
async function confirmEnd() {
  const end = wrapper
    .findAll("button")
    .find((button) => button.text() === "结束课堂");
  expect(end, "preparing classroom must expose an end action").toBeDefined();
  await end!.trigger("click");
  await wrapper.get('[data-test="confirm-end"]').trigger("click");
  await flushPromises();
}
it("explains a hard timeout without restarting paid analysis when opening its archive", async () => {
  state = "INTERRUPTED";
  archived = {
    simulationRunId: "run",
    versionNumber: 0,
    status: "INTERRUPTED",
    f3ModelContent: "REAL",
    summaryMarkdown: "",
    sessionRecord: {
      analysisStatus: "FAILED",
      events: [],
      session: { status: "INTERRUPTED", stopReason: "MODEL_HARD_TIMEOUT" },
      analysisWarnings: ["Execution interrupted; explicit analysis required"],
    },
  };
  await open();
  expect(wrapper.text()).toContain("模型请求超过安全时限，课堂已中止");
  expect(wrapper.text()).toContain("系统未自动重试");
  expect(wrapper.text()).toContain("重新分析会再次请求模型");
  expect(axios.post).not.toHaveBeenCalled();
  vi.mocked(axios.post).mockResolvedValueOnce({ data: archived });
  await wrapper
    .findAll("button")
    .find((button) => button.text() === "重试问题分析")!
    .trigger("click");
  await flushPromises();
  expect(axios.post).toHaveBeenCalledExactlyOnceWith(
    "/api/platform/lessons/lesson/simulations/run/analyze",
    {},
    { timeout: 360000 },
  );
});
it("reads a failed analysis without automatic retry and clears the failure after an explicit successful retry", async () => {
  state = "INTERRUPTED";
  archived = {
    simulationRunId: "run",
    versionNumber: 0,
    status: "INTERRUPTED",
    summaryMarkdown: "",
    sessionRecord: {
      analysisStatus: "PENDING",
      events: [],
      session: { status: "INTERRUPTED", analysisTimeoutSeconds: 4 },
      analysisWarnings: [],
    },
  };
  await open();
  vi.mocked(axios.post).mockImplementationOnce(async () => {
    archived = {
      ...archived,
      sessionRecord: {
        analysisStatus: "FAILED",
        events: [],
        session: { status: "INTERRUPTED", analysisTimeoutSeconds: 4 },
        analysisWarnings: ["ANALYSIS_HARD_TIMEOUT"],
      },
    };
    throw {
      response: { status: 502, data: { detail: "分析未完成，记录已保留" } },
    };
  });
  await wrapper
    .findAll("button")
    .find((button) => button.text() === "重试问题分析")!
    .trigger("click");
  await flushPromises();
  expect(wrapper.text()).toContain("问题分析超过安全时限，分析工作已停止");
  expect(wrapper.text()).toContain("分析未完成，记录已保留");
  expect(axios.post).toHaveBeenCalledExactlyOnceWith(
    "/api/platform/lessons/lesson/simulations/run/analyze",
    {},
    { timeout: 64000 },
  );
  vi.mocked(axios.post).mockResolvedValueOnce({
    data: {
      ...archived,
      sessionRecord: {
        analysisStatus: "COMPLETED",
        events: [],
        session: { status: "INTERRUPTED", analysisTimeoutSeconds: 4 },
        analysisWarnings: [],
      },
    },
  });
  await wrapper
    .findAll("button")
    .find((button) => button.text() === "重试问题分析")!
    .trigger("click");
  await flushPromises();
  expect(wrapper.text()).not.toContain("分析未完成，记录已保留");
  expect(wrapper.text()).not.toContain("问题分析超过安全时限，分析工作已停止");
  expect(axios.post).toHaveBeenCalledTimes(2);
});
it("ignores a late failed-analysis readback after switching lessons", async () => {
  state = "INTERRUPTED";
  archived = {
    simulationRunId: "run",
    versionNumber: 0,
    status: "INTERRUPTED",
    summaryMarkdown: "",
    sessionRecord: { analysisStatus: "PENDING", events: [], session: {} },
  };
  const router = await open();
  const readsBefore = vi
    .mocked(axios.get)
    .mock.calls.filter(([url]) => url.endsWith("/simulations/run")).length;
  let finishReadback!: (value: { data: unknown }) => void;
  const readback = new Promise<{ data: unknown }>((resolve) => {
    finishReadback = resolve;
  });
  const previousGet = vi.mocked(axios.get).getMockImplementation()!;
  vi.mocked(axios.get).mockImplementation((url, config) =>
    url.endsWith("/simulations/run") ? readback : previousGet(url, config),
  );
  vi.mocked(axios.post).mockRejectedValueOnce({
    response: { status: 502, data: { detail: "旧课堂分析失败" } },
  });
  await wrapper
    .findAll("button")
    .find((button) => button.text() === "重试问题分析")!
    .trigger("click");
  await flushPromises();
  expect(
    vi
      .mocked(axios.get)
      .mock.calls.filter(([url]) => url.endsWith("/simulations/run")),
  ).toHaveLength(readsBefore + 1);
  await router.push("/simulate?lessonId=other&versionId=v0");
  await flushPromises();
  finishReadback({
    data: {
      ...archived,
      sessionRecord: {
        analysisStatus: "FAILED",
        events: [],
        session: {},
        analysisWarnings: ["ANALYSIS_HARD_TIMEOUT"],
      },
    },
  });
  await flushPromises();
  expect(wrapper.text()).not.toContain("问题分析超过安全时限");
  expect(wrapper.text()).not.toContain("旧课堂分析失败");
  expect(axios.post).toHaveBeenCalledTimes(1);
});
it("can end a preparing launch without a remote session, then shows the confirmed terminal state", async () => {
  vi.mocked(axios.post).mockImplementation(async () => {
    state = "INTERRUPTED";
    return { data: { status: "INTERRUPTED" } };
  });
  await open();
  await confirmEnd();
  expect(axios.post).toHaveBeenCalledExactlyOnceWith(
    "/api/platform/simulation-launches/launch/end",
  );
  expect(wrapper.find(".live-section").exists()).toBe(false);
  expect(wrapper.text()).toContain("开始课堂推演");
});
it("shows durable stopping ahead of a stale running remote state and removes conflicting controls", async () => {
  state = "STOPPING";
  session = "remote";
  await open();
  expect(wrapper.get(".live-title").text()).toContain("正在结束");
  expect(axios.get).toHaveBeenCalledWith(
    "/api/platform/simulation-launches/launch/classroom",
  );
  const actions = wrapper
    .findAll(".live-actions button")
    .map((button) => button.text());
  expect(actions).not.toContain("暂停课堂");
  expect(actions).not.toContain("继续课堂");
  expect(actions).not.toContain("结束课堂");
});
it.each([false, true])(
  "reads the persisted stop intent after an unavailable remote response and keeps the error visible (bound=%s)",
  async (bound) => {
    if (bound) {
      session = "remote";
      state = "RUNNING";
    }
    vi.mocked(axios.post).mockImplementation(async () => {
      state = "STOPPING";
      throw { response: { status: 502, data: { detail: "远端暂不可用" } } };
    });
    await open();
    await confirmEnd();
    expect(wrapper.get(".live-title").text()).toContain("正在结束");
    expect(wrapper.get('[role="alert"]').text()).toContain("远端暂不可用");
    expect(wrapper.find('[data-test="confirm-end"]').exists()).toBe(false);
  },
);
