<script setup lang="ts">
defineProps<{ warnings?: string[] }>();
</script>

<template>
  <section
    v-if="warnings?.length"
    class="document-warnings"
    aria-label="文档保留提示"
    role="status"
  >
    <strong>请核对原稿</strong>
    <ul>
      <li v-for="warning in warnings" :key="warning">{{ warning }}</li>
    </ul>
    <slot />
  </section>
</template>

<style scoped>
.document-warnings {
  padding: 12px 14px;
  border: 1px solid #c8a154;
  border-radius: var(--radius-sm);
  color: #704900;
  background: #fff9eb;
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.document-warnings ul {
  margin: 5px 0;
  padding-left: 20px;
}
</style>
