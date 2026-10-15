<script setup lang="ts">
import type { LessonDocument } from "../types/lesson-document";
defineProps<{ document: LessonDocument; warnings?: string[] }>();
</script>
<template>
  <article class="lesson-document">
    <p v-for="warning in warnings || []" :key="warning" class="quality-note">
      {{ warning }}
    </p>
    <template v-for="block in document.blocks" :key="block.id">
      <div v-if="block.tableRows?.length" class="table-scroll">
        <table>
          <tbody>
            <tr
              v-for="(row, r) in block.tableRows"
              :key="block.rowIds?.[r] || r"
            >
              <td v-for="(cell, c) in row" :key="c">{{ cell }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <component
        :is="block.ordered ? 'ol' : 'ul'"
        v-else-if="block.items?.length"
      >
        <li v-for="(item, i) in block.items" :key="i">{{ item }}</li>
      </component>
      <h3 v-else-if="block.type === 'heading'">{{ block.text }}</h3>
      <p v-else>{{ block.text }}</p>
    </template>
  </article>
</template>
<style scoped>
.lesson-document {
  padding: clamp(20px, 4vw, 44px);
  line-height: 1.85;
  color: var(--color-text);
}
.lesson-document p,
.lesson-document td {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.table-scroll {
  overflow-x: auto;
  margin: 20px 0;
}
table {
  border-collapse: collapse;
  width: 100%;
  min-width: 480px;
}
td {
  border: 1px solid var(--color-border, #dce1e7);
  padding: 12px;
  vertical-align: top;
}
tr:first-child {
  background: var(--color-surface-soft, #f5f7fa);
  font-weight: 700;
}
.quality-note {
  padding: 12px;
  border-left: 3px solid var(--color-primary);
  background: var(--color-surface-soft);
}
</style>
