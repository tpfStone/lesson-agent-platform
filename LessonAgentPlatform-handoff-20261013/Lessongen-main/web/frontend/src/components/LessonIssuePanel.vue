<script setup lang="ts">
import { ref, watch, onUnmounted } from "vue";
import axios from "axios";
const props = defineProps<{ lessonId: string; versionId: string }>();
interface Issue {
  issueId: string;
  title: string;
  sourceModule: string;
  sourceDecision: string;
  resolutionStatus: string;
  revision: number;
}
interface Revalidation {
  issue_id: string;
  title: string;
  status: string;
  revision: number;
}
const items = ref<Issue[]>([]),
  pending = ref<Revalidation[]>([]),
  error = ref(""),
  loading = ref(false);
const notes = ref<Record<string, string>>({});
let generation = 0;
onUnmounted(() => {
  generation++;
});
const base = () =>
  `/api/platform/lessons/${encodeURIComponent(props.lessonId)}/versions/${encodeURIComponent(props.versionId)}/issues`;
async function load() {
  const current = ++generation;
  items.value = [];
  pending.value = [];
  error.value = "";
  notes.value = {};
  if (!props.lessonId || !props.versionId) return;
  loading.value = true;
  try {
    const [issues, revalidation] = await Promise.all([
      axios.get<{ items: Issue[] }>(base()),
      axios.get<Revalidation[]>(`${base()}/revalidations`),
    ]);
    if (current !== generation) return;
    items.value = issues.data.items;
    pending.value = revalidation.data;
  } catch (reason) {
    if (current === generation)
      error.value = axios.isAxiosError(reason)
        ? reason.response?.data?.detail || reason.message
        : "问题列表加载失败";
  } finally {
    if (current === generation) loading.value = false;
  }
}
watch(() => [props.lessonId, props.versionId], load, { immediate: true });
async function update(
  id: string,
  revision: number,
  status: string,
  revalidation = false,
) {
  const reason = notes.value[id]?.trim();
  if (!reason) return;
  const current = generation;
  try {
    await axios.patch(
      `${base()}/${revalidation ? "revalidations/" : ""}${encodeURIComponent(id)}`,
      { expectedRevision: revision, resolutionStatus: status, reason },
    );
    if (current === generation) await load();
  } catch (failure) {
    if (current === generation)
      error.value = axios.isAxiosError(failure)
        ? failure.response?.data?.detail || failure.message
        : "保存处理结果失败";
  }
}
const label = (value: string) =>
  ({
    OPEN: "待处理",
    RESOLVED: "已验证解决",
    DISMISSED: "不采纳",
    PENDING: "待复核",
    PERSISTS: "问题仍存在",
    NOT_APPLICABLE: "不适用于此版本",
    ACCEPTED: "已采纳建议",
    REJECTED: "已拒绝建议",
  })[value] || value;
</script>
<template>
  <section class="v2-card issue-panel">
    <h2>问题与复核</h2>
    <p v-if="loading">正在加载……</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!loading && !items.length && !pending.length && !error">
      此版本暂无已同步的问题。
    </p>
    <article v-for="item in items" :key="item.issueId">
      <h3>{{ item.title }}</h3>
      <p>
        {{ item.sourceModule }} · 来源决策：{{
          label(item.sourceDecision || "OPEN")
        }}
        · 处理状态：{{ label(item.resolutionStatus) }}
      </p>
      <label
        >处理依据<input
          v-model="notes[item.issueId]"
          maxlength="2000"
          placeholder="记录核对结果和依据"
      /></label>
      <button
        :disabled="!notes[item.issueId]?.trim()"
        @click="update(item.issueId, item.revision, 'RESOLVED')"
      >
        确认已解决
      </button>
      <button
        :disabled="!notes[item.issueId]?.trim()"
        @click="update(item.issueId, item.revision, 'OPEN')"
      >
        保持待处理
      </button>
    </article>
    <article v-for="item in pending" :key="`revalidate-${item.issue_id}`">
      <h3>旧版本问题：{{ item.title }}</h3>
      <p>在此版本：{{ label(item.status) }}</p>
      <label
        >复核依据<input
          v-model="notes[item.issue_id]"
          maxlength="2000"
          placeholder="核对本版本后填写依据"
      /></label>
      <button
        :disabled="!notes[item.issue_id]?.trim()"
        @click="update(item.issue_id, item.revision, 'RESOLVED', true)"
      >
        此版本已解决
      </button>
      <button
        :disabled="!notes[item.issue_id]?.trim()"
        @click="update(item.issue_id, item.revision, 'PERSISTS', true)"
      >
        问题仍存在
      </button>
    </article>
  </section>
</template>
<style scoped>
.issue-panel {
  padding: 24px;
}
.issue-panel article {
  border-top: 1px solid var(--color-border);
  padding: 16px 0;
}
.issue-panel h3 {
  font-size: 16px;
}
.issue-panel p {
  color: var(--color-text-secondary);
}
.issue-panel label {
  display: grid;
  gap: 6px;
}
.issue-panel input {
  padding: 9px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
}
.issue-panel button {
  margin: 10px 8px 0 0;
  padding: 8px 12px;
  cursor: pointer;
}
.issue-panel button:disabled {
  cursor: default;
  opacity: 0.5;
}
</style>
