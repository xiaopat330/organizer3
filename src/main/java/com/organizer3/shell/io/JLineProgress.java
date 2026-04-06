package com.organizer3.shell.io;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.List;

/**
 * Progress bar implementation that renders in the JLine3 status area as a single line:
 * <pre>
 *   -> scanning &lt;path&gt; | [████████░░░░░░░░░░░░]  42/890  → processing "actress"
 * </pre>
 *
 * <p>The path section is fixed-width (abbreviated if needed) so the bar never shifts.
 * The bar is rendered in yellow; the actress name in the detail section is rendered in red.
 */
class JLineProgress implements Progress {

    private static final int BAR_WIDTH  = 20;
    private static final int PATH_WIDTH = 14;
    private static final String FILLED = "█";
    private static final String EMPTY  = "░";

    private final JLineCommandIO io;
    private final String title;
    private final int total;
    private int current;
    private String detail = "";

    JLineProgress(JLineCommandIO io, String title, int total) {
        this.io    = io;
        this.title = title;
        this.total = total;
        render();
    }

    @Override
    public void advance() {
        advance(1);
    }

    @Override
    public void advance(int n) {
        current = Math.min(current + n, total);
        render();
    }

    @Override
    public void setLabel(String label) {
        this.detail = label;
        render();
    }

    private void render() {
        int filled = (total > 0) ? (current * BAR_WIDTH / total) : 0;
        int digits = String.valueOf(total).length();
        String fraction = String.format("%" + digits + "d/%d", current, total);
        String path = abbreviate(title, PATH_WIDTH);

        AttributedStringBuilder sb = new AttributedStringBuilder();

        // Fixed-width path section
        sb.append("\u2192 ");
        sb.append(String.format("%-" + PATH_WIDTH + "s", path));
        sb.append(" | ");

        // Progress bar in yellow
        sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW));
        sb.append("[");
        sb.append(FILLED.repeat(filled));
        sb.append(EMPTY.repeat(BAR_WIDTH - filled));
        sb.append("]");
        sb.style(AttributedStyle.DEFAULT);

        // Fixed-width fraction
        sb.append(" ");
        sb.append(fraction);

        // Detail: → processing "name" with name in red
        if (!detail.isEmpty()) {
            sb.append("  \u2192 processing \"");
            sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED));
            sb.append(detail);
            sb.style(AttributedStyle.DEFAULT);
            sb.append("\"");
        }

        io.renderStatusAttributed(List.of(sb.toAttributedString()));
    }

    private static String abbreviate(String s, int width) {
        if (s.length() <= width) return s;
        return s.substring(s.length() - width);
    }

    @Override
    public void close() {
        io.clearStatusBar();
    }
}
