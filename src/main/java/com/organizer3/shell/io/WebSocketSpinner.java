package com.organizer3.shell.io;

/**
 * {@link Spinner} implementation that sends spinner events over a WebSocket.
 */
class WebSocketSpinner implements Spinner {

    private final WebSocketCommandIO io;

    WebSocketSpinner(WebSocketCommandIO io) {
        this.io = io;
    }

    @Override
    public void setStatus(String message) {
        io.sendSpinnerUpdate(message);
    }

    @Override
    public void close() {
        io.sendSpinnerStop();
    }
}
