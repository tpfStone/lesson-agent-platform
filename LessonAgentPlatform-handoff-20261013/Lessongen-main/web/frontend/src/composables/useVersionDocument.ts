import { ref, watch, onScopeDispose, type Ref } from "vue";
import axios from "axios";
import type { VersionDocument } from "../types/lesson-document";
export function useVersionDocument(
  lessonId: Ref<string>,
  versionId: Ref<string>,
) {
  const snapshot = ref<VersionDocument | null>(null);
  const state = ref<"loading" | "ready" | "fallback" | "error">("loading");
  const error = ref("");
  let generation = 0;
  onScopeDispose(() => {
    generation++;
  });
  watch(
    [lessonId, versionId],
    async ([lesson, version]) => {
      const request = ++generation;
      snapshot.value = null;
      error.value = "";
      state.value = "loading";
      if (!lesson || !version) return;
      try {
        const next = (
          await axios.get<VersionDocument>(
            `/api/platform/lessons/${encodeURIComponent(lesson)}/versions/${encodeURIComponent(version)}/document`,
          )
        ).data;
        if (request !== generation) return;
        if (next.lessonId !== lesson || next.versionId !== version)
          throw new Error("版本结构与当前选择不一致");
        const document = next.document;
        if (
          !document ||
          typeof document !== "object" ||
          Array.isArray(document) ||
          !Array.isArray(document.blocks) ||
          document.blocks.some(
            (block) =>
              !block || typeof block !== "object" || Array.isArray(block),
          )
        ) {
          throw new Error("教案结构已损坏，无法加载正文，请重试。");
        }
        if (!["1.0", "1.1"].includes(next.document.schemaVersion))
          throw new Error("不支持此结构版本");
        snapshot.value = next;
        state.value = next.bindingStatus === "VERIFIED" ? "ready" : "fallback";
      } catch (reason) {
        if (request !== generation) return;
        if (
          axios.isAxiosError(reason) &&
          reason.response?.data?.code === "LESSON_DOCUMENT_NOT_FOUND"
        ) {
          state.value = "fallback";
        } else {
          state.value = "error";
          error.value =
            reason instanceof Error ? reason.message : "结构加载失败，请重试。";
        }
      }
    },
    { immediate: true },
  );
  return { snapshot, state, error };
}
