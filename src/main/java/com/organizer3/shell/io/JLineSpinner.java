package com.organizer3.shell.io;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spinner implementation that animates in the JLine3 status bar.
 * Runs a daemon thread that updates the status area every 80ms.
 */
class JLineSpinner implements Spinner {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final int FRAME_MS = 80;

    private final JLineCommandIO io;
    private final AtomicReference<String> label;
    private volatile boolean running = true;
    private final Thread thread;

    JLineSpinner(JLineCommandIO io, String initialLabel) {
        this.io = io;
        this.label = new AtomicReference<>(initialLabel);
        this.thread = new Thread(this::spin, "mount-spinner");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    @Override
    public void setStatus(String message) {
        label.set(message);
    }

    private void spin() {
        int i = 0;
        while (running) {
            io.renderStatus(List.of(FRAMES[i % FRAMES.length] + " " + label.get()));
            i++;
            try {
                Thread.sleep(FRAME_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            thread.join(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        io.clearStatusBar();
    }
}
