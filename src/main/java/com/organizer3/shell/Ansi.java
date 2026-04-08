package com.organizer3.shell;

/**
 * Shared ANSI escape codes for terminal output.
 */
public final class Ansi {

    public static final String BOLD   = "\033[1m";
    public static final String DIM    = "\033[2m";
    public static final String WHITE  = "\033[97m";
    public static final String RED    = "\033[91m";
    public static final String GREEN  = "\033[92m";
    public static final String YELLOW = "\033[93m";
    public static final String CYAN   = "\033[96m";
    public static final String RESET  = "\033[0m";

    private Ansi() {}
}
