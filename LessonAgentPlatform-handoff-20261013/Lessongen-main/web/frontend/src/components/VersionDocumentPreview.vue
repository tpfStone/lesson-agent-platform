<script setup lang="ts">
import { toRef } from "vue";
import { useVersionDocument } from "../composables/useVersionDocument";
import LessonDocumentPreview from "./LessonDocumentPreview.vue";
import ReviewDocumentPreview from "./ReviewDocumentPreview.vue";
import DocumentWarnings from "./DocumentWarnings.vue";
const props = defineProps<{
  lessonId: string;
  versionId: string;
  content: string;
}>();
const { snapshot, state, error } = useVersionDocument(
  toRef(props, "lessonId"),
  toRef(props, "versionId"),
);
</script>
<template>
  <p v-if="state === 'loading'" role="status">正在加载所选版本结构……</p>
  <p v-else-if="state === 'error'" role="alert">{{ error }}</p>
  <LessonDocumentPreview
    v-else-if="snapshot && state === 'ready'"
    :document="snapshot.document"
    :warnings="snapshot.warnings"
  />
  <template v-else>
    <p>此版本使用正文兼容显示，教学表格尚未确认恢复。</p>
    <DocumentWarnings :warnings="snapshot?.warnings" />
    <ReviewDocumentPreview :content="content" />
  </template>
</template>
