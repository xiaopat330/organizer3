package com.organizer3.shell;

import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.shell.io.PlainCommandIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TitleTable — formatted table rendering for Title rows.
 */
class TitleTableTest {

    private StringWriter output;
    private PlainCommandIO io;

    @BeforeEach
    void setUp() {
        output = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(output));
    }

    @Test
    void headerContainsColumnNames() {
        List<TitleTable.Column> cols = List.of(
                TitleTable.Column.plain("CODE", Title::getCode),
                TitleTable.Column.plain("LABEL", Title::getLabel)
        );

        TitleTable.print(List.of(title("ABP-001", "ABP")), cols, "", io);

        String out = output.toString();
        assertTrue(out.contains("CODE"));
        assertTrue(out.contains("LABEL"));
    }

    @Test
    void separatorRowAppearsAfterHeader() {
        List<TitleTable.Column> cols = List.of(TitleTable.Column.plain("CODE", Title::getCode));

        TitleTable.print(List.of(title("ABP-001", "ABP")), cols, "", io);

        String[] lines = output.toString().split("\n");
        // Line 0: header, Line 1: separator (contains ─)
        assertTrue(lines[1].contains("─"), "Second line should be the separator");
    }

    @Test
    void columnWidthFitsLongestValue() {
        // Single column — separator width == max(header, data values)
        List<TitleTable.Column> cols = List.of(TitleTable.Column.plain("CD", Title::getCode));
        Title short_ = title("AB-001", "AB");
        Title long_  = title("SSIS-00123", "SSIS");

        TitleTable.print(List.of(short_, long_), cols, "", io);

        // Separator line should be exactly 10 "─" chars (length of "SSIS-00123")
        String sep = output.toString().split("\n")[1];
        assertEquals(10, sep.codePointCount(0, sep.length()),
                "Separator width should match the longest value");
    }

    @Test
    void indentAppliedToAllLines() {
        List<TitleTable.Column> cols = List.of(TitleTable.Column.plain("CODE", Title::getCode));

        TitleTable.print(List.of(title("ABP-001", "ABP")), cols, "  ", io);

        for (String line : output.toString().split("\n")) {
            if (!line.isBlank()) {
                assertTrue(line.startsWith("  "), "Each line should start with indent: [" + line + "]");
            }
        }
    }

    @Test
    void emptyListRendersOnlyHeaderAndSeparator() {
        List<TitleTable.Column> cols = List.of(TitleTable.Column.plain("CODE", Title::getCode));

        TitleTable.print(List.of(), cols, "", io);

        String[] lines = output.toString().split("\n");
        assertEquals(2, lines.length, "Empty list should produce header + separator only");
    }

    @Test
    void multipleColumnsAreSeparatedByPipe() {
        List<TitleTable.Column> cols = List.of(
                TitleTable.Column.plain("CODE", Title::getCode),
                TitleTable.Column.plain("LABEL", Title::getLabel)
        );

        TitleTable.print(List.of(title("ABP-001", "ABP")), cols, "", io);

        String dataRow = output.toString().split("\n")[2];
        assertTrue(dataRow.contains("|"), "Data rows should contain column separator");
    }

    @Test
    void coloredColumnStripsAnsiInPlainOutput() {
        List<TitleTable.Column> cols = List.of(
                TitleTable.Column.colored("CODE", Title::getCode, "\033[96m")
        );

        TitleTable.print(List.of(title("ABP-001", "ABP")), cols, "", io);

        // PlainCommandIO strips ANSI codes, so the value should appear plain
        String dataRow = output.toString().split("\n")[2];
        assertTrue(dataRow.contains("ABP-001"));
        assertFalse(dataRow.contains("\033["), "ANSI codes should be stripped by PlainCommandIO");
    }

    @Test
    void columnWidthAtLeastAsWideAsHeader() {
        // Header "CODE" is wider than any data value here
        List<TitleTable.Column> cols = List.of(TitleTable.Column.plain("CODE", t -> "X"));

        TitleTable.print(List.of(title("ABP-001", "ABP")), cols, "", io);

        String[] lines = output.toString().split("\n");
        // Separator should be at least 4 chars wide (length of "CODE")
        assertTrue(lines[1].replace("─", "").isBlank());
        assertTrue(lines[1].length() >= 4);
    }

    // --- Helpers ---

    private Title title(String code, String label) {
        return Title.builder()
                .id(1L).code(code).baseCode(code).label(label).seqNum(1)
                .locations(List.of(TitleLocation.builder()
                        .titleId(1L).volumeId("a").partitionId("queue")
                        .path(Path.of("/queue/" + code))
                        .lastSeenAt(LocalDate.now()).build()))
                .build();
    }
}
