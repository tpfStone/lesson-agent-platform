import { expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import LessonDocumentPreview from "../src/components/LessonDocumentPreview.vue";
import {
  parseProjectedLesson,
  sectionDocument,
  navigationPreview,
} from "../src/utils/projectedLesson";

it("shows the selected historical structure and only its warnings", () => {
  const document = {
    schemaVersion: "1.1",
    blocks: [
      {
        id: "t",
        type: "table",
        tableRows: [
          ["教师", "备注"],
          ["第一行\n第二行 | 竖线", String.raw`C:\new`],
        ],
      },
    ],
  };
  const preview = navigationPreview({
    content: "历史投影",
    structured_content: document,
    content_format: "LESSON_DOCUMENT",
    structure_warnings: ["历史版本提示"],
  });
  const wrapper = mount(LessonDocumentPreview, { props: preview });
  expect(wrapper.findAll("tr")).toHaveLength(2);
  expect(wrapper.findAll("td")[2]!.text()).toBe("第一行\n第二行 | 竖线");
  expect(preview.warnings).toEqual(["历史版本提示"]);
  expect(document.blocks[0]?.tableRows[1]?.[1]).toBe(String.raw`C:\new`);
});

it("does not guess tables or decode unknown and mixed historical text", () => {
  const content = String.raw`left | right
C:\new | literal\nsecond`;
  for (const content_format of [
    undefined,
    "UNKNOWN",
    "MIXED_FALLBACK",
    "PLAIN_TEXT",
  ]) {
    const preview = navigationPreview({ content, content_format });
    const wrapper = mount(LessonDocumentPreview, { props: preview });
    expect(wrapper.find("table").exists()).toBe(false);
    expect(wrapper.findAll("p").at(-1)!.text()).toBe(content);
    if (content_format !== "PLAIN_TEXT")
      expect(preview.warnings.length).toBeGreaterThan(0);
  }
});

it("renders projected table escapes once and retains plain text backslashes", () => {
  const content = String.raw`课前说明 C:\new

环节 | 教师活动 | 备注
导入 | 第一行\n第二行 \| 竖线 | C:\\new
结束 | <script>危险</script> | 尾标`;
  const document = parseProjectedLesson(content, "1.1");
  const wrapper = mount(LessonDocumentPreview, { props: { document } });
  expect(wrapper.findAll("tr")).toHaveLength(3);
  expect(
    wrapper
      .findAll("tr")[1]!
      .findAll("td")
      .map((c) => c.text()),
  ).toEqual(["导入", "第一行\n第二行 | 竖线", String.raw`C:\new`]);
  expect(wrapper.find("p").text()).toBe(String.raw`课前说明 C:\new`);
  expect(wrapper.find("script").exists()).toBe(false);
  expect(content).toContain(String.raw`第一行\n第二行 \| 竖线`);
});

it("keeps legacy or unknown projection escapes literal", () => {
  const content = String.raw`Folder | Note
C:\new | C:\next`;
  for (const schema of [undefined, "1.0"] as const) {
    const result = parseProjectedLesson(content, schema);
    expect(result.blocks[0]?.tableRows?.[1]).toEqual([
      String.raw`C:\new`,
      String.raw`C:\next`,
    ]);
  }
});

it("retains the preamble and real header before a teaching row", () => {
  const document = {
    schemaVersion: "1.1",
    blocks: [
      {
        id: "table",
        type: "teaching_process",
        rowIds: ["title", "header", "activity"],
        tableRows: [
          ["课堂", ""],
          ["教师活动", "学生活动"],
          ["提问", "讨论"],
        ],
        teachingProcess: { rows: [{ sourceRowIndex: 2 }] },
      },
    ],
  };
  expect(
    sectionDocument(document, {
      blockId: "table",
      rowId: "activity",
      sourceRowIndex: 2,
    })?.blocks[0]?.tableRows,
  ).toEqual(document.blocks[0]?.tableRows);
});

it("maps a section by its row identity, rejecting stale row offsets", () => {
  const document = {
    schemaVersion: "1.1",
    blocks: [
      {
        id: "table",
        type: "table",
        rowIds: ["header", "row1", "row2"],
        tableRows: [
          ["环节", "备注"],
          ["导入", "保留\n换行"],
          ["练习", "其他行"],
        ],
      },
    ],
  };
  expect(
    sectionDocument(document, {
      blockId: "table",
      rowId: "row1",
      sourceRowIndex: 1,
    })?.blocks[0]?.tableRows,
  ).toEqual([
    ["环节", "备注"],
    ["导入", "保留\n换行"],
  ]);
  expect(
    sectionDocument(document, {
      blockId: "table",
      rowId: "row1",
      sourceRowIndex: 2,
    }),
  ).toBeNull();
  expect(document.blocks[0]?.tableRows).toHaveLength(3);
});

it("renders a whole chapter with all paragraphs and table rows in document order", () => {
  const document = {
    schemaVersion: "1.1",
    blocks: [
      { id: "intro", type: "paragraph", text: "五年级" },
      { id: "h", type: "heading", level: 1, text: "教学过程" },
      { id: "p", type: "paragraph", text: "观察图形" },
      {
        id: "t",
        type: "table",
        tableRows: [
          ["教师", "学生"],
          ["提问", "观察"],
          ["引导", "解释"],
        ],
      },
      { id: "next", type: "heading", level: 1, text: "作业" },
    ],
  };
  const chapter = sectionDocument(document, { blockIds: ["h", "p", "t"] });
  expect(chapter?.blocks.map((block) => block.id)).toEqual(["h", "p", "t"]);
  const wrapper = mount(LessonDocumentPreview, {
    props: { document: chapter! },
  });
  expect(wrapper.text()).toContain("教学过程");
  expect(wrapper.text()).toContain("观察图形");
  expect(wrapper.findAll("tr")).toHaveLength(3);
  expect(wrapper.text()).not.toContain("五年级");
  expect(wrapper.text()).not.toContain("作业");
  expect(sectionDocument(document, { blockIds: ["h", "missing"] })).toBeNull();
  expect(sectionDocument(document, { blockIds: ["h", "t"] })).toBeNull();
});
