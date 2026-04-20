package com.organizer3.command;

import com.organizer3.media.BackgroundThumbnailWorker;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * Runtime control for the background thumbnail sync worker.
 *
 * <pre>
 *   background-thumbs          # alias for "status"
 *   background-thumbs status   # show enabled/queue/last-generated
 *   background-thumbs on       # enable
 *   background-thumbs off      # disable
 * </pre>
 */
@RequiredArgsConstructor
public class BackgroundThumbsCommand implements Command {

    private final BackgroundThumbnailWorker worker;

    @Override public String name() { return "background-thumbs"; }

    @Override public String description() {
        return "Toggle / inspect the background thumbnail sync worker (on|off|status)";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        String sub = args.length > 1 ? args[1].toLowerCase() : "status";
        switch (sub) {
            case "on" -> {
                worker.setEnabled(true);
                io.println("Background thumbnail sync: ON");
            }
            case "off" -> {
                worker.setEnabled(false);
                io.println("Background thumbnail sync: OFF");
            }
            case "status" -> printStatus(io);
            default -> io.println("Usage: background-thumbs [on|off|status]");
        }
    }

    private void printStatus(CommandIO io) {
        io.println("Background thumbnail sync: " + (worker.isEnabled() ? "ON" : "OFF"));
        io.println("  Actionable queue size (last cycle): " + worker.getLastQueueSize());
        io.println("  Total generated (this session):     " + worker.getTotalGenerated());
        io.println("  Total evicted (this session):       " + worker.getTotalEvicted());
        long lastGen = worker.getLastGeneratedAt();
        if (lastGen > 0) {
            Duration age = Duration.between(Instant.ofEpochMilli(lastGen), Instant.now());
            io.println("  Last generated: " + worker.getLastGeneratedCode()
                    + " (" + formatDuration(age) + " ago)");
        } else {
            io.println("  Last generated: (none this session)");
        }
    }

    private static String formatDuration(Duration d) {
        if (d.toMinutes() < 1) return d.getSeconds() + "s";
        if (d.toHours()   < 1) return d.toMinutes() + "m";
        return d.toHours() + "h" + (d.toMinutesPart()) + "m";
    }
}
