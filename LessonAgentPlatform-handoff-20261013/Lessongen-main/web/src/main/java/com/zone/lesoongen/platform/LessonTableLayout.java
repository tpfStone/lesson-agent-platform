package com.zone.lesoongen.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/** Semantic regeneration: wide tables retain every cell, with an explicit repeated locator column. */
final class LessonTableLayout {
    private LessonTableLayout() {}

    static List<List<Integer>> columnGroups(LessonDocument.Block block) {
        int width = block.tableRows().stream().mapToInt(List::size).max().orElse(0);
        if (width <= 5) return List.of(IntStream.range(0, width).boxed().toList());
        List<List<Integer>> groups = new ArrayList<>();
        for (int start = 1; start < width; start += 4) {
            List<Integer> columns = new ArrayList<>(List.of(0));
            for (int column = start; column < Math.min(start + 4, width); column++) columns.add(column);
            groups.add(List.copyOf(columns));
        }
        return List.copyOf(groups);
    }

    static int headerRows(LessonDocument.Block block) {
        if (block.teachingProcess() != null) {
            // Preserve preamble rows, but locate the actual header from authoritative columns.
            for (int row = 0; row < block.tableRows().size(); row++)
                if (block.tableRows().get(row).equals(block.teachingProcess().columns())) return row + 1;
        }
        return block.tableRows().isEmpty() ? 0 : 1;
    }

    static String cell(List<String> row, int column) { return column < row.size() ? row.get(column) : ""; }
    static boolean allowRowSplit(List<String> row) {
        return row.stream().anyMatch(cell -> cell.length() > 300 || cell.lines().count() > 10);
    }
    static String groupLabel(int index, int count) {
        return "宽表列组 " + (index + 1) + "/" + count + "（首列重复，便于逐行对照）";
    }
}
