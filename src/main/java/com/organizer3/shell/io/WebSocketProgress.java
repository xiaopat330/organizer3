package com.organizer3.shell.io;

/**
 * {@link Progress} implementation that sends progress events over a WebSocket.
 */
class WebSocketProgress implements Progress {

    private final WebSocketCommandIO io;
    private final int total;
    private int current = 0;
    private String detail = null;

    WebSocketProgress(WebSocketCommandIO io, int total) {
        this.io = io;
        this.total = total;
    }

    @Override
    public void advance() {
        advance(1);
    }

    @Override
    public void advance(int n) {
        current = Math.min(current + n, total);
        io.sendProgressUpdate(current, total, detail);
    }

    @Override
    public void setLabel(String label) {
        this.detail = label;
        io.sendProgressUpdate(current, total, detail);
    }

    @Override
    public void close() {
        io.sendProgressStop();
    }
}
