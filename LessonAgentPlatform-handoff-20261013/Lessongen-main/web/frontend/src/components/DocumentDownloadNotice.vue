<script setup lang="ts">
import { RouterLink } from "vue-router";
defineProps<{ lessonId?: string; sourceHref?: string }>();
</script>

<template>
  <p class="document-download-notice">
    下载稿采用统一排版，图片、文本框及复杂表格布局可能无法完整保留。
    <a v-if="sourceHref" :href="sourceHref" download>下载原稿核对</a>
    <RouterLink
      v-else-if="lessonId"
      :to="`/lessons/${encodeURIComponent(lessonId)}`"
      >前往教案空间核对原稿</RouterLink
    >
    <span v-else>如有原稿，请保留并核对。</span>
  </p>
</template>

<style scoped>
.document-download-notice {
  margin: 12px 0;
  padding: 12px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  color: var(--color-text-secondary);
  background: #f6f9ff;
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.document-download-notice a {
  color: var(--color-primary);
  text-decoration: underline;
  text-underline-offset: 3px;
}
</style>
