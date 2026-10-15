import { expect, it, vi } from "vitest";
import { effectScope, ref, nextTick } from "vue";
import axios from "axios";
import { useVersionDocument } from "../src/composables/useVersionDocument";
vi.mock("axios", () => ({
  default: { get: vi.fn(), isAxiosError: (x: any) => x?.isAxiosError },
}));
const data = (id: string) => ({
  lessonId: "lesson",
  versionId: id,
  bindingStatus: "VERIFIED",
  warnings: [],
  document: {
    schemaVersion: "1.1",
    blocks: [{ id: "b", type: "paragraph", text: id }],
  },
});
it("lateResponseCannotReplaceNewVersion", async () => {
  const pending: Record<string, (value: any) => void> = {};
  vi.mocked(axios.get).mockImplementation(
    (url: string) =>
      new Promise((resolve) => {
        pending[url.includes("/A/") ? "A" : "B"] = resolve;
      }),
  );
  const scope = effectScope(),
    lesson = ref("lesson"),
    version = ref("A");
  const state = scope.run(() => useVersionDocument(lesson, version))!;
  version.value = "B";
  await nextTick();
  pending.B!({ data: data("B") });
  await nextTick();
  pending.A!({ data: data("A") });
  await nextTick();
  expect(state.snapshot.value?.versionId).toBe("B");
  scope.stop();
});
it("networkFailureIsVisibleInsteadOfAnEmptyLesson", async () => {
  vi.mocked(axios.get).mockRejectedValueOnce(new Error("unavailable"));
  const scope = effectScope();
  const state = scope.run(() => useVersionDocument(ref("lesson"), ref("A")))!;
  await nextTick();
  expect(state.state.value).toBe("error");
  expect(state.snapshot.value).toBeNull();
  scope.stop();
});
