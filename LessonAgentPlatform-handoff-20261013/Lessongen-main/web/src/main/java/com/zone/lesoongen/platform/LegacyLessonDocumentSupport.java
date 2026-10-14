package com.zone.lesoongen.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.zone.lesoongen.platform.LessonDocument.TeachingProcess;
import com.zone.lesoongen.platform.LessonDocument.TeachingProcessRow;

/** Frozen schema 1.0 derivation: validate historical data without rewriting its semantics. */
final class LegacyLessonDocumentSupport {
    private LegacyLessonDocumentSupport() {}
    private static final Pattern DURATION = Pattern.compile("(?i)(?:约\\s*)?(\\d{1,3})\\s*(?:分钟|min(?:ute)?s?)");
    public static TeachingProcess teachingProcess(List<List<String>> rows) {
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
            if (row.stream().allMatch(String::isBlank)) continue;
            String teacher = cell(row, indexes.teacher());
            String student = cell(row, indexes.student());
            String evaluation = cell(row, indexes.evaluation());
            String titleSource = indexes.section() >= 0 ? cell(row, indexes.section()) : teacher;
            Matcher duration = DURATION.matcher(titleSource);
            Integer minutes = duration.find() ? Integer.valueOf(duration.group(1)) : null;
            String title = firstParagraph(titleSource)
                    .replaceAll("(?i)[（(]?\\s*(?:约\\s*)?\\d{1,3}\\s*(?:分钟|min(?:ute)?s?)[）)]?", "")
                    .replaceFirst("^\\s*\\d{1,3}[.．、)）]\\s*", "")
                    .replaceAll("[\\s/|：:\\-—]+$", "").strip();
            if (title.isBlank() || title.length() > 60) title = "教学环节 " + (result.size() + 1);
            List<String> extras = new ArrayList<>();
            for (int cellIndex = 0; cellIndex < row.size(); cellIndex++) {
                if (cellIndex != indexes.section() && cellIndex != indexes.teacher() && cellIndex != indexes.student()
                        && cellIndex != indexes.evaluation()) {
                    extras.add(row.get(cellIndex));
                }
            }
            result.add(new TeachingProcessRow(rowIndex, title, minutes, teacher, student,
                    evaluation, extras));
        }
        if (result.isEmpty()) return null;
        return new TeachingProcess(List.copyOf(rows.get(headerIndex)), result);
    }

    public static String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n")
                .replace('\r', '\n').replace("\u0000", "").strip();
    }

    private static HeaderIndexes headerIndexes(List<String> row) {
        int section = -1, teacher = -1, student = -1, evaluation = -1;
        for (int index = 0; index < row.size(); index++) {
            String header = normalizeHeader(row.get(index));
            if (section < 0 && isSectionHeader(header)) section = index;
            if (teacher < 0 && isTeacherHeader(header)) teacher = index;
            if (student < 0 && isStudentHeader(header)) student = index;
            if (evaluation < 0 && isEvaluationHeader(header)) evaluation = index;
        }
        return new HeaderIndexes(section, teacher, student, evaluation);
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
        return (header.contains("教学环节")
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

    private record HeaderIndexes(int section, int teacher, int student, int evaluation) {
    }
}
