package com.organizer3.shell;

import com.organizer3.model.Title;
import com.organizer3.shell.io.CommandIO;

import java.util.List;
import java.util.function.Function;

/**
 * Renders a formatted table of {@link Title} rows to a {@link CommandIO}.
 *
 * <p>Columns are defined via {@link Column}, which separates the raw (plain-text) value used for
 * width calculation from the display value that may contain ANSI escape codes.
 */
public class TitleTable {

    private static final String RESET = "\033[0m";
    private static final String CYAN  = "\033[96m";

    public record Column(String header, Function<Title, String> raw, Function<Title, String> display) {

        public static Column plain(String header, Function<Title, String> value) {
            return new Column(header, value, value);
        }

        public static Column colored(String header, Function<Title, String> value, String ansiCode) {
            return new Column(header, value, t -> ansiCode + value.apply(t) + RESET);
        }
    }

    /**
     * Prints a table of titles to {@code io} with column widths auto-fitted to content.
     *
     * @param titles  rows to render
     * @param columns column definitions (header + value extractors)
     * @param indent  prefix string applied to every line
     * @param io      output target
     */
    public static void print(List<Title> titles, List<Column> columns, String indent, CommandIO io) {
        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            widths[i] = columns.get(i).header().length();
        }
        for (Title t : titles) {
            for (int i = 0; i < columns.size(); i++) {
                widths[i] = Math.max(widths[i], columns.get(i).raw().apply(t).length());
            }
        }

        StringBuilder header = new StringBuilder(indent);
        StringBuilder sep    = new StringBuilder(indent);
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) { header.append(" | "); sep.append("─┼─"); }
            header.append(CYAN).append(padRight(columns.get(i).header(), widths[i])).append(RESET);
            sep.append("─".repeat(widths[i]));
        }
        io.printlnAnsi(header.toString().stripTrailing());
        io.println(sep.toString());

        for (Title t : titles) {
            StringBuilder row = new StringBuilder(indent);
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) row.append(" | ");
                String raw     = columns.get(i).raw().apply(t);
                String display = columns.get(i).display().apply(t);
                row.append(display);
                if (i < columns.size() - 1) {
                    row.append(" ".repeat(widths[i] - raw.length()));
                }
            }
            io.printlnAnsi(row.toString());
        }
    }

    private static String padRight(String s, int width) {
        return s + " ".repeat(Math.max(0, width - s.length()));
    }
}
