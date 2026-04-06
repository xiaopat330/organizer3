package com.organizer3.shell.io;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.Status;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;

/**
 * Renders an interactive selection list in the terminal and returns the chosen item.
 *
 * <p>Enters raw mode so keystrokes are read one-at-a-time without echo. Arrow keys move
 * the cursor; Enter confirms the selection; Escape or 'q' cancels.
 *
 * <p>Only usable on a real TTY. {@link JLineCommandIO} guards the terminal-type check
 * before delegating here.
 */
class ListPicker {

    private static final String CURSOR    = "\033[1m▶\033[0m ";
    private static final String NO_CURSOR = "  ";
    private static final String HINT      = "  \033[2m[↑↓ select · Enter open · Esc cancel]\033[0m";

    private final Terminal terminal;

    ListPicker(Terminal terminal) {
        this.terminal = terminal;
    }

    Optional<String> pick(List<String> items) {
        if (items.isEmpty()) return Optional.empty();

        PrintWriter out = terminal.writer();
        int[] selected = {0};  // array so the lambda below can capture it

        Status jlineStatus = Status.getStatus(terminal, false);
        if (jlineStatus != null) jlineStatus.suspend();

        render(out, items, selected[0]);

        Attributes savedAttrs = terminal.enterRawMode();
        NonBlockingReader reader = terminal.reader();
        try {
            while (true) {
                int ch = read(reader);
                if (ch < 0) continue;

                if (ch == '\r' || ch == '\n') {
                    clearAll(out, items.size());
                    return Optional.of(items.get(selected[0]));
                } else if (ch == 3 || ch == 'q') {   // Ctrl-C or q
                    clearAll(out, items.size());
                    return Optional.empty();
                } else if (ch == 27) {                // ESC or escape sequence
                    int next = read(reader);
                    if (next < 0 || next == 27) {     // plain ESC — cancel
                        clearAll(out, items.size());
                        return Optional.empty();
                    } else if (next == '[') {
                        int code = read(reader);
                        if      (code == 'A' && selected[0] > 0)                    selected[0]--;
                        else if (code == 'B' && selected[0] < items.size() - 1)     selected[0]++;
                    }
                }
                redraw(out, items, selected[0]);
            }
        } catch (Exception e) {
            clearAll(out, items.size());
            return Optional.empty();
        } finally {
            terminal.setAttributes(savedAttrs);
            if (jlineStatus != null) jlineStatus.restore();
        }
    }

    private int read(NonBlockingReader reader) {
        try {
            return reader.read(500);
        } catch (IOException e) {
            return -1;
        }
    }

    private void render(PrintWriter out, List<String> items, int selected) {
        for (int i = 0; i < items.size(); i++) {
            out.print((i == selected ? CURSOR : NO_CURSOR) + items.get(i) + "\r\n");
        }
        out.print(HINT + "\r\n");
        out.flush();
    }

    private void redraw(PrintWriter out, List<String> items, int selected) {
        int totalLines = items.size() + 1;
        out.print("\033[" + totalLines + "A");  // cursor up to first item line
        for (int i = 0; i < items.size(); i++) {
            out.print("\r\033[2K" + (i == selected ? CURSOR : NO_CURSOR) + items.get(i) + "\r\n");
        }
        out.print("\r\033[2K" + HINT + "\r\n");
        out.flush();
    }

    private void clearAll(PrintWriter out, int itemCount) {
        int totalLines = itemCount + 1;
        out.print("\033[" + totalLines + "A");  // cursor up to first item line
        out.print("\033[J");                    // erase from cursor to end of screen
        out.flush();
    }
}
