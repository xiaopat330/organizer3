package com.organizer3.shell;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * Builds the interactive prompt string from current session state.
 *
 * <p>The prompt is rendered inverted green. When a volume is mounted the mount
 * ID is shown; the prompt always ends with a filled triangle and a plain space.
 *
 * Examples (rendered inverted green):
 *   ▶              (no volume mounted)
 *   [MOUNT → a] ▶  (volume mounted)
 */
public class PromptBuilder {

    public AttributedString build(SessionContext ctx) {
        AttributedStringBuilder sb = new AttributedStringBuilder();

        if (ctx.getMountedVolumeId() != null) {
            sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).inverse());
            sb.append("[MOUNT \u2192 ").append(ctx.getMountedVolumeId()).append("] ");
            sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN));
        } else {
            sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).inverse());
            sb.append("[UNMOUNTED] ");
            sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED));
        }

        sb.append("\u25B6");
        sb.style(AttributedStyle.DEFAULT);
        sb.append(" ");

        return sb.toAttributedString();
    }
}
