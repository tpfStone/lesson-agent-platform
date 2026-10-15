import { expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import LessonDocumentPreview from "../src/components/LessonDocumentPreview.vue";
it("renders authoritative table cells and escapes untrusted HTML", () => {
  const wrapper = mount(LessonDocumentPreview, {
    props: {
      document: {
        schemaVersion: "1.1",
        blocks: [
          {
            id: "b",
            type: "teaching_process",
            tableRows: [
              ["教师", "学生", "额外"],
              ["<script>alert(1)</script>", "回答", "保留"],
            ],
          },
        ],
      },
    },
  });
  expect(wrapper.findAll("tr")).toHaveLength(2);
  expect(wrapper.text()).toContain("保留");
  expect(wrapper.find("script").exists()).toBe(false);
});
