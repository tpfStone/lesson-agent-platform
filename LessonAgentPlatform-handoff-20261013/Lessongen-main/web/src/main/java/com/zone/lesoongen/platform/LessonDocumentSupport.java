package com.zone.lesoongen.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.zone.lesoongen.platform.LessonDocument.Block;
import com.zone.lesoongen.platform.LessonDocument.TeachingProcess;
import com.zone.lesoongen.platform.LessonDocument.TeachingProcessRow;

/** Shared parsing and compatibility projection helpers for {@link LessonDocument}. */
public final class LessonDocumentSupport {
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^(?:[一二三四五六七八九十百]+[、．.]|第[一二三四五六七八九十百]+[章节部分]|\\d{1,2}[、．.])\\s*.+");
    private static final Pattern BULLET = Pattern.compile("^[•·▪●○◆◇*-]\\s*(.+)$");
    private static final Pattern ORDERED_ITEM = Pattern.compile("^(?:\\d+[.)、]|[（(]?[一二三四五六七八九十]+[）)、.])\\s*(.+)$");
    private static final Pattern DURATION = Pattern.compile("(?i)(?:约\\s*)?(\\d{1,3})\\s*(?:分钟|min(?:ute)?s?)");
    private static final Set<String> KNOWN_HEADINGS = Set.of(
            "基本信息", "课程信息", "教学依据", "教材分析", "学情分析", "教学目标", "学习目标",
            "教学重点", "教学难点", "教学准备", "教学过程", "学习评价", "作业设计", "板书设计",
            "教学反思", "课后反思", "评价设计", "达成证据");

    private LessonDocumentSupport() {
    }

    public static LessonDocument fromPlainText(String content) {
        String normalized = normalize(content);
        if (normalized.isBlank()) return new LessonDocument(LessonDocument.SCHEMA_VERSION, List.of());
        List<Block> blocks = new ArrayList<>();
        List<String> listItems = new ArrayList<>();
        Boolean listOrdered = null;
        List<List<String>> tableRows = new ArrayList<>();
        int[] nextId = {1};

        for (String raw : normalized.split("\\n", -1)) {
            String line = raw.strip();
            if (line.isBlank()) {
                flushList(blocks, listItems, listOrdered, nextId);
                listOrdered = null;
                flushTable(blocks, tableRows, nextId);
                continue;
            }
            if (looksLikePipeRow(line)) {
                flushList(blocks, listItems, listOrdered, nextId);
                listOrdered = null;
                List<String> cells = parsePipeRow(line);
                if (!isSeparator(cells)) tableRows.add(cells);
                continue;
            }
            flushTable(blocks, tableRows, nextId);
            if (looksLikeHeading(line)) {
                flushList(blocks, listItems, listOrdered, nextId);
                listOrdered = null;
                blocks.add(new Block(id(nextId), "heading", inferredHeadingLevel(line), line,
                        null, List.of(), List.of(), null));
                continue;
            }
            Matcher ordered = ORDERED_ITEM.matcher(line);
            Matcher bullet = BULLET.matcher(line);
            if (ordered.matches() || bullet.matches()) {
                boolean isOrdered = ordered.matches();
                if (listOrdered != null && listOrdered != isOrdered) {
                    flushList(blocks, listItems, listOrdered, nextId);
                }
                listOrdered = isOrdered;
                listItems.add(isOrdered ? ordered.group(1).strip() : bullet.group(1).strip());
                continue;
            }
            flushList(blocks, listItems, listOrdered, nextId);
            listOrdered = null;
            blocks.add(new Block(id(nextId), "paragraph", null, line,
                    null, List.of(), List.of(), null));
        }
        flushList(blocks, listItems, listOrdered, nextId);
        flushTable(blocks, tableRows, nextId);
        return new LessonDocument(LessonDocument.SCHEMA_VERSION, blocks);
    }

    public static Block tableBlock(String id, List<List<String>> rows) {
        List<String> rowIds = rows.stream().map(row -> "r-" + UUID.randomUUID()).toList();
        TeachingProcess process = teachingProcess(rows, rowIds);
        return new Block(id, process == null ? "table" : "teaching_process", null, null,
                null, List.of(), rows, process, rowIds, columnBindings(rows));
    }

    public static TeachingProcess teachingProcess(List<List<String>> rows) {
        return teachingProcess(rows, List.of());
    }

    public static TeachingProcess teachingProcess(List<List<String>> rows, List<String> rowIds) {
        if (rows == null || rows.size() < 2) return null;
        int headerIndex = -1;
        HeaderIndexes indexes = null;
        for (int index = 0; index < Math.min(3, rows.size()); index++) {
            HeaderIndexes candidate = headerIndexes(rows.get(index));
            if (candidate.teacher() >= 0 && candidate.student() >= 0) {
                headerIndex = index;
                indexes = candidate;
                break;
            }
        }
        if (headerIndex < 0 || indexes == null) return null;

        List<TeachingProcessRow> result = new ArrayList<>();
        for (int rowIndex = headerIndex + 1; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            if (row.stream().allMatch(String::isBlank) || isSeparator(row)
                    || row.stream().map(LessonDocumentSupport::normalizeHeader).toList()
                            .equals(rows.get(headerIndex).stream().map(LessonDocumentSupport::normalizeHeader).toList())) continue;
            String teacher = cell(row, indexes.teacher());
            String student = cell(row, indexes.student());
            String evaluation = cell(row, indexes.evaluation());
            if (teacher.isBlank() && student.isBlank() && evaluation.isBlank()) continue;
            String titleSource = indexes.section() >= 0 ? cell(row, indexes.section()) : teacher;
            String durationSource = indexes.duration() >= 0 ? cell(row, indexes.duration()) : titleSource;
            if (durationSource.matches("\\d{1,3}")) durationSource += "分钟";
            Matcher duration = DURATION.matcher(durationSource);
            Integer minutes = duration.find() ? Integer.valueOf(duration.group(1)) : null;
            String title = firstParagraph(titleSource)
                    .replaceAll("(?i)[（(]?\\s*(?:约\\s*)?\\d{1,3}\\s*(?:分钟|min(?:ute)?s?)[）)]?", "")
                    .replaceFirst("^\\s*\\d{1,3}[.．、)）]\\s*", "")
                    .replaceAll("[\\s/|：:\\-—]+$", "").strip();
            if (title.isBlank() || title.length() > 60) title = "教学环节 " + (result.size() + 1);
            List<String> extras = new ArrayList<>();
            for (int cellIndex = 0; cellIndex < row.size(); cellIndex++) {
                if (cellIndex != indexes.section() && cellIndex != indexes.teacher() && cellIndex != indexes.student()
                        && cellIndex != indexes.evaluation() && cellIndex != indexes.duration()) {
                    extras.add(row.get(cellIndex));
                }
            }
            result.add(new TeachingProcessRow(rowIndex, title, minutes, teacher, student,
                    evaluation, extras, rowIndex < rowIds.size() ? rowIds.get(rowIndex) : null));
        }
        if (result.isEmpty()) return null;
        return new TeachingProcess(List.copyOf(rows.get(headerIndex)), result);
    }

    public static String renderText(LessonDocument document) {
        List<String> rendered = new ArrayList<>();
        for (Block block : document.blocks()) {
            switch (block.type()) {
                case "heading", "paragraph" -> add(rendered, block.text());
                case "list" -> {
                    List<String> lines = new ArrayList<>();
                    for (int index = 0; index < block.items().size(); index++) {
                        String marker = Boolean.TRUE.equals(block.ordered()) ? (index + 1) + ". " : "• ";
                        lines.add(marker + block.items().get(index));
                    }
                    add(rendered, String.join("\n", lines));
                }
                case "table", "teaching_process" -> add(rendered, renderTable(block.tableRows()));
                default -> add(rendered, block.text());
            }
        }
        return String.join("\n\n", rendered).strip();
    }

    public static boolean looksLikeHeading(String text) {
        String value = normalize(text).strip();
        if (value.isBlank() || value.length() > 60 || value.contains("。")) return false;
        if (NUMBERED_HEADING.matcher(value).matches()) return true;
        String stripped = value.replaceAll("[：:]$", "").strip();
        return KNOWN_HEADINGS.contains(stripped);
    }

    public static int inferredHeadingLevel(String text) {
        String value = normalize(text).strip();
        return NUMBERED_HEADING.matcher(value).matches() ? 1 : 2;
    }

    public static String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n")
                .replace('\r', '\n').replace("\u0000", "").strip();
    }

    private static HeaderIndexes headerIndexes(List<String> row) {
        int section = -1, teacher = -1, student = -1, evaluation = -1, duration = -1;
        for (int index = 0; index < row.size(); index++) {
            String header = normalizeHeader(row.get(index));
            if (section < 0 && isSectionHeader(header)) section = index;
            if (teacher < 0 && isTeacherHeader(header)) teacher = index;
            if (student < 0 && isStudentHeader(header)) student = index;
            if (evaluation < 0 && isEvaluationHeader(header)) evaluation = index;
            if (duration < 0 && (header.equals("时间") || header.equals("时长") || header.equals("duration")
                    || header.equals("time") || header.equals("时间分钟") || header.equals("时长分钟"))) duration = index;
        }
        return new HeaderIndexes(section, teacher, student, evaluation, duration);
    }

    private static String normalizeHeader(String value) {
        return normalize(value).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。、；：！？（）【】《》·—]+", "");
    }

    private static boolean isTeacherHeader(String header) {
        return header.contains("教师活动") || header.equals("教学过程")
                || header.contains("teacheractivit") || header.equals("teacher");
    }

    private static boolean isSectionHeader(String header) {
        return (header.equals("环节") || header.equals("stage") || header.contains("教学环节")
                || (header.contains("环节") && (header.contains("时间") || header.contains("时长"))))
                && !header.contains("教师活动");
    }

    private static boolean isStudentHeader(String header) {
        return header.contains("学生活动") || header.contains("学习任务") || header.contains("学生学习")
                || header.contains("studentactivit") || header.equals("student");
    }

    private static boolean isEvaluationHeader(String header) {
        return header.contains("评价") || header.contains("调控") || header.contains("设计意图")
                || header.contains("assessment") || header.contains("evaluation");
    }

    private static String cell(List<String> row, int index) {
        return index >= 0 && index < row.size() ? normalize(row.get(index)) : "";
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }

    private static String firstParagraph(String text) {
        String first = firstLine(text);
        int separator = first.indexOf(" / ");
        return separator < 0 ? first : first.substring(0, separator);
    }

    private static void flushList(List<Block> blocks, List<String> items, Boolean ordered, int[] nextId) {
        if (items.isEmpty()) return;
        blocks.add(new Block(id(nextId), "list", null, null, Boolean.TRUE.equals(ordered),
                List.copyOf(items), List.of(), null));
        items.clear();
    }

    private static void flushTable(List<Block> blocks, List<List<String>> rows, int[] nextId) {
        if (rows.isEmpty()) return;
        blocks.add(tableBlock(id(nextId), List.copyOf(rows)));
        rows.clear();
    }

    private static boolean looksLikePipeRow(String line) {
        return line.indexOf('|') >= 0 && parsePipeRow(line).size() >= 2;
    }

    static List<String> parsePipeRow(String line) {
        String value = line.strip();
        boolean wrapped = value.startsWith("|");
        if (wrapped) value = value.substring(1);
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean escaped = false;
        boolean endedWithDelimiter = false;
        for (char c : value.toCharArray()) {
            endedWithDelimiter = false;
            if (escaped) {
                if (c != '|' && c != '\\' && c != 'n') cell.append('\\');
                cell.append(c == 'n' ? '\n' : c);
                escaped = false;
            } else if (c == '\\') escaped = true;
            else if (c == '|') {
                cells.add(cell.toString().strip()); cell.setLength(0); endedWithDelimiter = true;
            } else cell.append(c);
        }
        if (escaped) cell.append('\\');
        if (!endedWithDelimiter || !wrapped) cells.add(cell.toString().strip());
        return List.copyOf(cells);
    }

    static boolean isSeparator(List<String> row) {
        return !row.isEmpty() && row.stream().allMatch(c -> c.strip().matches(":?-{3,}:?"));
    }

    public static Map<String, Integer> columnBindings(List<List<String>> rows) {
        for (var row : rows.stream().limit(3).toList()) {
            var indexes = headerIndexes(row);
            if (indexes.teacher() < 0 || indexes.student() < 0) continue;
            Map<String, Integer> result = new LinkedHashMap<>();
            if (indexes.section() >= 0) result.put("title", indexes.section());
            result.put("teacherActivity", indexes.teacher());
            result.put("studentActivity", indexes.student());
            if (indexes.evaluation() >= 0) result.put("evaluation", indexes.evaluation());
            if (indexes.duration() >= 0) result.put("durationMinutes", indexes.duration());
            return result;
        }
        return Map.of();
    }

    private static String renderTable(List<List<String>> rows) {
        return rows.stream().map(row -> String.join(" | ", row)).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private static String id(int[] nextId) {
        return "b-" + UUID.randomUUID();
    }

    private static void add(List<String> output, String value) {
        String normalized = normalize(value);
        if (!normalized.isBlank()) output.add(normalized);
    }

    private record HeaderIndexes(int section, int teacher, int student, int evaluation, int duration) {
    }
}
