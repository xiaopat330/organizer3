package com.organizer3.shell;

/**
 * Builds the interactive prompt string from current session state.
 *
 * Examples:
 *   organizer [*DRYRUN*] >         (no volume, dry-run)
 *   organizer:vol-a [*DRYRUN*] >   (volume mounted, dry-run)
 *   organizer:vol-a >              (volume mounted, armed)
 */
public class PromptBuilder {

    public String build(SessionContext ctx) {
        StringBuilder sb = new StringBuilder("organizer");
        if (ctx.getMountedVolumeId() != null) {
            sb.append(":vol-").append(ctx.getMountedVolumeId());
        }
        if (ctx.isDryRun()) {
            sb.append(" [*DRYRUN*]");
        }
        sb.append(" > ");
        return sb.toString();
    }
}
