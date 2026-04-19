package com.organizer3.shell;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * Builds the interactive prompt string from current session state.
 *
 * <p>Segments rendered left to right:
 * <ul>
 *   <li>Mount badge: green=[MOUNT → id], red=[UNMOUNTED]
 *   <li>Live badge: yellow [*LIVE*] when dry-run is off (absent in normal dry-run mode)
 *   <li>Triangle ▶ and space
 * </ul>
 *
 * Examples:
 *   [UNMOUNTED] ▶
 *   [MOUNT → bg] ▶
 *   [MOUNT → bg] [*LIVE*] ▶
 */
public class PromptBuilder {

    public AttributedString build(SessionContext ctx) {
        AttributedStringBuilder sb = new AttributedStringBuilder();

        if (ctx.getMountedVolumeId() != null) {
            sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).inverse());
            sb.append("[MOUNT \u2192 ").append(ctx.getMountedVolumeId()).append("] ");
        } else {
            sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).inverse());
            sb.append("[UNMOUNTED] ");
        }

        if (!ctx.isDryRun()) {
            sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).inverse());
            sb.append("[*LIVE*] ");
        }

        sb.style(AttributedStyle.DEFAULT);
        sb.append("\u25B6 ");

        return sb.toAttributedString();
    }
}
