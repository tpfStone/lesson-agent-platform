import type { LessonDocument } from "../types/lesson-document";

export function navigationPreview(
  snapshot?: {
    content?: string;
    current_content?: string;
    structured_content?: LessonDocument | null;
    content_format?: string | null;
    structure_warnings?: string[] | null;
  } | null,
): { document: LessonDocument; warnings: string[] } {
  const warnings = [...(snapshot?.structure_warnings || [])];
  if (snapshot?.structured_content)
    return { document: snapshot.structured_content, warnings };
  if (snapshot?.content_format === "MIXED_FALLBACK")
    warnings.unshift(
      "此版本含无法完整映射回表格的修改，正文与转义符按保存原文显示。",
    );
  else if (snapshot?.content_format !== "PLAIN_TEXT")
    warnings.unshift(
      "此历史版本缺少可核对的格式来源，正文与转义符按保存原文显示。",
    );
  return {
    document: {
      schemaVersion: "1.1",
      blocks: [
        {
          id: "literal-content",
          type: "paragraph",
          text: snapshot?.content ?? snapshot?.current_content ?? "",
        },
      ],
    },
    warnings,
  };
}

export function parseProjectedLesson(
  content: string,
  schemaVersion?: "1.0" | "1.1",
): LessonDocument {
  const lines = content.replace(/\r\n?/g, "\n").split("\n");
  const blocks: LessonDocument["blocks"] = [];
  let text: string[] = [];
  function flush() {
    if (text.length)
      blocks.push({
        id: `p${blocks.length}`,
        type: "paragraph",
        text: text.join("\n"),
      });
    text = [];
  }
  for (let i = 0; i < lines.length; i++) {
    const cells = splitCells(lines[i]!, schemaVersion === "1.1");
    const next =
      i + 1 < lines.length
        ? splitCells(lines[i + 1]!, schemaVersion === "1.1")
        : [];
    // A multirow projection only. An isolated prose pipe or path stays literal.
    if (cells.length > 1 && next.length === cells.length) {
      flush();
      const rows = [cells];
      while (i + 1 < lines.length) {
        const row = splitCells(lines[i + 1]!, schemaVersion === "1.1");
        if (row.length !== cells.length) break;
        rows.push(row);
        i++;
      }
      blocks.push({ id: `t${blocks.length}`, type: "table", tableRows: rows });
    } else if (!lines[i]!.trim()) flush();
    else text.push(lines[i]!);
  }
  flush();
  return { schemaVersion: "1.1", blocks };
}

function splitCells(line: string, decode: boolean): string[] {
  const cells: string[] = [];
  let value = "";
  for (let i = 0; i < line.length; i++) {
    const char = line[i]!;
    if (
      decode &&
      char === "\\" &&
      i + 1 < line.length &&
      ["\\", "|", "n"].includes(line[i + 1]!)
    ) {
      const escaped = line[++i]!;
      value += escaped === "n" ? "\n" : escaped;
    } else if (char === "|") {
      cells.push(value.trim());
      value = "";
    } else value += char;
  }
  cells.push(value.trim());
  return cells;
}

export function sectionDocument(
  document: LessonDocument,
  source: { blockId: string; rowId?: string; sourceRowIndex?: number },
): LessonDocument | null {
  const block = document.blocks.find((b) => b.id === source.blockId);
  if (!block) return null;
  if (!source.rowId)
    return { schemaVersion: document.schemaVersion, blocks: [block] };
  const index = source.sourceRowIndex;
  if (
    index == null ||
    index < 1 ||
    block.rowIds?.[index] !== source.rowId ||
    !block.tableRows?.[index]
  )
    return null;
  const firstActivity = Math.min(
    ...(block.teachingProcess?.rows.map((row) => row.sourceRowIndex) || [1]),
  );
  const preambleEnd =
    Number.isInteger(firstActivity) &&
    firstActivity > 0 &&
    firstActivity <= index
      ? firstActivity
      : 1;
  return {
    schemaVersion: document.schemaVersion,
    blocks: [
      {
        ...block,
        tableRows: [
          ...block.tableRows.slice(0, preambleEnd),
          block.tableRows[index]!,
        ],
        rowIds: [...block.rowIds.slice(0, preambleEnd), source.rowId],
      },
    ],
  };
}
