<script setup lang="ts">
import type { OptimizationScope } from "../types/api";
const model = defineModel<OptimizationScope>({ required: true });
const options = [
  ["/procedure_steps", "教学过程（含环节时间、活动与提问）"],
  ["/learning_objectives", "学习目标"],
  ["/homework", "作业"],
  ["/assessment_plan", "评价设计"],
  ["/content_analysis", "教学内容分析"],
  ["/student_analysis", "学情分析"],
  ["/key_points", "教学重点"],
  ["/difficult_points", "教学难点"],
  ["/teaching_strategy", "教学策略"],
  ["/resources", "教学资源"],
  ["/teaching_artifacts", "教学材料"],
  ["/differentiation", "差异化支持"],
  ["/board_design", "板书"],
  ["/reflection", "反思与备注"],
  ["/curriculum_standards", "课程标准依据"],
  ["/design_thesis", "设计主张"],
  ["/driving_question", "驱动问题"],
  ["/learning_trajectory", "学习轨迹"],
  ["/references", "参考资料"],
];
function changeMode(event: Event) {
  if (!event.target || !("value" in event.target)) return;
  const mode = event.target.value;
  if (mode !== "local" && mode !== "full") return;
  model.value = { mode, editablePaths: [] };
}
function toggle(path: string, event: Event) {
  const checked = (event.target as HTMLInputElement).checked;
  model.value = {
    mode: "local",
    editablePaths: checked
      ? [...model.value.editablePaths, path]
      : model.value.editablePaths.filter((item) => item !== path),
  };
}
</script>

<template>
  <fieldset class="scope-fields" aria-label="本次可修改范围">
    <legend>本次可修改范围</legend>
    <label>
      修改方式
      <select :value="model.mode" @change="changeMode">
        <option value="local">局部优化：仅修改所选栏目</option>
        <option value="full">全文优化：允许修改整份教案内容</option>
      </select>
    </label>
    <template v-if="model.mode === 'local'">
      <p>
        未选栏目保持原样，包括原有作业、备注及空白栏目。范围外的原有缺项仅作提示，不会自动补写；范围内仍需通过质量校验。
      </p>
      <div class="scope-options">
        <label v-for="[path, label] in options" :key="path">
          <input
            type="checkbox"
            :value="path"
            :checked="model.editablePaths.includes(path!)"
            @change="toggle(path!, $event)"
          />
          {{ label }}
        </label>
      </div>
      <p v-if="!model.editablePaths.length" role="alert">
        请至少选择一个可修改栏目。
      </p>
    </template>
    <p v-else>将按全文完整性要求检查并修改教案；课程身份和来源信息保持不变。</p>
    <p>下方优化重点和已有问题仅用于提出建议，不会扩大这里选定的范围。</p>
  </fieldset>
</template>

<style scoped>
.scope-fields {
  min-width: 0;
  margin: 1rem 0;
  padding: 1rem;
  border: 1px solid var(--line, #cbd5e1);
  border-radius: 0.75rem;
}
.scope-fields legend {
  padding: 0 0.3rem;
  font-weight: 650;
}
.scope-fields p {
  margin: 0.75rem 0;
  line-height: 1.6;
}
.scope-options {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 0.6rem;
}
.scope-options label {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
}
.scope-options input {
  width: auto;
  margin-top: 0.3rem;
}
.scope-fields select {
  display: block;
  width: 100%;
  margin-top: 0.4rem;
}
</style>
