# Proposal: Web Terminal

## Motivation

All administration — mounting volumes, syncing, managing actresses, loading YAML — is done through the JLine3 interactive shell. This works well, but requires a terminal window. Since the Javalin web server is already running alongside the shell, it's natural to expose the same command interface through the browser.

This is a single-user, LAN-only feature. No auth, no sessions, no concurrent commands. One person running commands from either a terminal or a browser tab (or both).

---

## 1. Why This Is Architecturally Clean

The existing codebase already has the critical abstraction: `CommandIO`. Every command talks through this interface — it never touches the terminal directly:

```java
void execute(String[] args, SessionContext ctx, CommandIO io);
```

There are already two implementations:

| Implementation | Context | What it does |
|---------------|---------|-------------|
| `JLineCommandIO` | Live terminal | Renders output above status bar, animates spinners/progress via JLine3 `Status` |
| `PlainCommandIO` | Tests, non-TTY | Writes to a `PrintWriter`, spinners/progress are no-ops |

A web terminal is a **third implementation**: `WebSocketCommandIO` that routes output to the browser over a WebSocket instead of to a terminal. No command code changes. No refactoring of the dispatch loop. The abstraction boundary already exists exactly where it needs to be.

---

## 2. WebSocket Communication

### Why WebSocket (Not REST)

Commands are long-running and produce streaming output. A sync might print hundreds of lines over 30 seconds. A mount command shows a spinner for 2-3 seconds. REST doesn't model this well — you'd need polling or SSE. WebSocket gives bidirectional, real-time communication that maps directly to the shell interaction model:

- **Browser → Server:** command strings (`mount a`, `sync all`, `volumes`)
- **Server → Browser:** output events (text lines, spinner start/stop, progress updates, pick requests)

### Javalin WebSocket Support

Javalin 6 has built-in WebSocket support. No new dependencies:

```java
app.ws("/ws/terminal", ws -> {
    ws.onConnect(ctx -> { ... });
    ws.onMessage(ctx -> { ... });
    ws.onClose(ctx -> { ... });
});
```

### Protocol

All messages are JSON. Server → browser messages have a `type` field that the frontend uses to route to the appropriate renderer.

**Browser → Server:**

```json
{ "type": "command", "text": "mount a" }
```

```json
{ "type": "pick-response", "index": 2 }
```

**Server → Browser:**

```json
{ "type": "output", "text": "Connected. Volume 'a' is now active." }
```

```json
{ "type": "spinner-start", "label": "Connecting to //nas/share" }
{ "type": "spinner-update", "label": "Authenticating..." }
{ "type": "spinner-stop" }
```

```json
{ "type": "progress-start", "label": "scanning", "total": 890 }
{ "type": "progress-update", "current": 42, "detail": "ABP-123" }
{ "type": "progress-stop" }
```

```json
{ "type": "pick", "items": ["volume-a  /share/a  never synced", "volume-b  /share/b  Apr 5"] }
```

```json
{ "type": "prompt", "text": "[MOUNT → a] ▶ " }
```

```json
{ "type": "ready" }
```

The `ready` message signals that a command has finished and the frontend can accept new input. While a command is running, the input field is disabled (or shows a visual busy state).

---

## 3. `WebSocketCommandIO`

The new `CommandIO` implementation. Lives in `com.organizer3.shell.io` alongside the existing implementations.

### Output

```java
@Override
public void println(String message) {
    send(Map.of("type", "output", "text", stripAnsi(message)));
}

@Override
public void printlnAnsi(String message) {
    send(Map.of("type", "output", "text", stripAnsi(message), "ansi", message));
}
```

Text output is sent as a WebSocket message. ANSI escape codes are stripped for the plain `text` field; the raw ANSI is included in a separate `ansi` field so the frontend can choose which to render (plain text, or pass ANSI through an ANSI-to-HTML converter).

### Spinner

```java
@Override
public Spinner startSpinner(String label) {
    send(Map.of("type", "spinner-start", "label", label));
    return new WebSocketSpinner(this, label);
}
```

`WebSocketSpinner` sends `spinner-update` messages on `setStatus()` and `spinner-stop` on `close()`. No daemon thread needed — the browser handles the animation with CSS.

### Progress

```java
@Override
public Progress startProgress(String label, int total) {
    send(Map.of("type", "progress-start", "label", label, "total", total));
    return new WebSocketProgress(this, label, total);
}
```

`WebSocketProgress` sends `progress-update` on `advance()` / `setLabel()` and `progress-stop` on `close()`. The browser renders a real HTML progress bar — actually better-looking than the terminal's `█░` approximation.

### Pick (Interactive Selection)

This is the only interaction that requires a round-trip:

```java
@Override
public Optional<String> pick(List<String> items) {
    send(Map.of("type", "pick", "items", items));
    // Block until the browser sends back a pick-response
    int index = waitForPickResponse();  // blocks on a CountDownLatch/queue
    return index >= 0 ? Optional.of(items.get(index)) : Optional.empty();
}
```

The command thread blocks (which is fine — single user, single command at a time) until the browser sends back a `pick-response` message with the selected index (or -1 for cancel). The frontend renders the items as a clickable list.

### Thread Safety

Command execution happens on a dedicated thread (not the Javalin WebSocket handler thread). When a command message arrives:

```java
ws.onMessage(ctx -> {
    // Parse command, dispatch on a worker thread
    executor.submit(() -> dispatch(text, webSocketCommandIO));
});
```

This keeps the WebSocket handler responsive for receiving `pick-response` messages while a command is running.

---

## 4. Server-Side Wiring

### WebSocket Handler

A new class `WebTerminalHandler` registered in `WebServer`:

```java
public class WebTerminalHandler {
    private final Map<String, Command> commands;
    private final SessionContext session;
    private final PromptBuilder promptBuilder;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public void register(Javalin app) {
        app.ws("/ws/terminal", ws -> {
            ws.onConnect(this::onConnect);
            ws.onMessage(this::onMessage);
            ws.onClose(this::onClose);
        });
    }
}
```

### Shared Session

The `WebTerminalHandler` receives the same `SessionContext` and command map that `OrganizerShell` uses. Both the terminal shell and the web terminal operate on the same session state. Mount a volume in the browser → it's mounted in the terminal prompt. This is intentional for single-user use.

### Dispatch

The dispatch logic is extracted from `OrganizerShell` into a shared utility so both the shell and the web handler use the same two-word command resolution, argument parsing, and error handling:

```java
public class CommandDispatcher {
    private final Map<String, Command> commands;

    public void dispatch(String line, SessionContext ctx, CommandIO io) {
        // Same logic currently in OrganizerShell.dispatch()
    }
}
```

`OrganizerShell` and `WebTerminalHandler` both use `CommandDispatcher`. This is a small refactor — extract the existing `dispatch()` method body, no behavior change.

### WebServer Changes

`WebServer` gains a new constructor parameter for the command infrastructure:

```java
public WebServer(int port, TitleBrowseService browseService,
                 ActressBrowseService actressBrowseService, Path coversRoot,
                 CommandDispatcher dispatcher, SessionContext session) {
    // ... existing setup ...
    new WebTerminalHandler(dispatcher, session).register(app);
}
```

### Application.java

Wired manually per the existing pattern:

```java
// Extract dispatcher from command map
CommandDispatcher dispatcher = new CommandDispatcher(commandMap);

// Shell uses dispatcher
OrganizerShell shell = new OrganizerShell(session, dispatcher);

// Web server gets dispatcher + session for web terminal
WebServer webServer = new WebServer(browseService, actressBrowseService,
        coverPath.root(), dispatcher, session);
```

---

## 5. Frontend Layout: Two Options

The server-side code (WebSocket handler, `WebSocketCommandIO`, `CommandDispatcher`) is identical regardless of which layout is chosen. The only difference is how the frontend positions and shows/hides the terminal container. Both options share the same internal components (output area, status area, input line — described in §5.3).

### Option A: Admin Tab (Full-Page View)

An "Admin" nav button in the header, alongside Actresses, Collections, Archives, and Queues. Clicking it switches the main content area to the terminal, exactly like clicking "Actresses" switches to the actress grid. Click the app name or any other nav button to go back to browsing.

Follows the existing view-switching pattern in `app.js` (`showTitlesView`, `showActressView`, etc.): hide the current content, show the terminal container.

```
┌──────────────────────────────────────────────────────────────────┐
│  app-name | Actresses | Collections | Archives | Queues | Admin  │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Organizer3 — type 'help' for available commands                  │
│                                                                   │
│  > volumes                                                        │
│                                                                   │
│    NAS-A                                                          │
│    ──────────────────────────────────────────                     │
│    ID    PATH          STRUCTURE    LAST SYNCED                   │
│    ──────────────────────────────────────────                     │
│    a     /media/a      conventional Apr 5, 2026  ● connected     │
│    b     /media/b      conventional Mar 28, 2026                  │
│                                                                   │
│  > sync all                                                       │
│  ⠹ scanning stars/  [████████░░░░░░░░]  342/890  → "ABP-123"    │
│                                                                   │
│  [MOUNT → a] ▶ _                                                  │
└──────────────────────────────────────────────────────────────────┘
```

The terminal fills the entire content area below the header. Monospace font, dark background matching the existing `#0d0d0d` theme.

HTML additions:

```html
<!-- Nav button in header -->
<div class="nav-divider"></div>
<button class="nav-link" id="admin-btn">Admin</button>

<!-- Terminal view (hidden by default, shown when Admin is clicked) -->
<div id="admin-terminal" style="display:none">
  <div class="terminal-output" id="terminal-output"></div>
  <div class="terminal-status" id="terminal-status"></div>
  <div class="terminal-input-line">
    <span class="terminal-prompt" id="terminal-prompt">[UNMOUNTED] ▶</span>
    <input class="terminal-input" type="text" id="terminal-input"
           placeholder="type a command…" autocomplete="off" spellcheck="false">
  </div>
</div>
```

### Option B: Slide-Out Panel (Bottom Drawer)

A toggle button (e.g., a `⌨` icon or "Terminal" label) in the header opens a panel that slides up from the bottom of the viewport — similar to Chrome DevTools or the VS Code integrated terminal. The browse content area above it shrinks to make room. Closing the panel restores the full browse area.

```
┌──────────────────────────────────────────────────────────────────┐
│  app-name | Actresses | Collections | Archives | Queues | [⌨]    │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│           browse content area                                     │
│         (title cards, actress grid, etc.)                         │
│         (shrinks when panel is open)                              │
│                                                                   │
├─── drag handle ──────────────────────────────────────────────────┤
│  Organizer3 — type 'help' for available commands                  │
│                                                                   │
│  > mount a                                                        │
│  Connected. Volume 'a' is now active.                             │
│  Loaded index: 4521 title(s), 892 actress(es).                   │
│                                                                   │
│  [MOUNT → a] ▶ _                                                  │
└──────────────────────────────────────────────────────────────────┘
```

The panel defaults to ~30-40% of the viewport height. A drag handle at the top edge allows resizing. When closed, it's completely hidden and the browse area reclaims the full viewport.

HTML additions:

```html
<!-- Toggle button in header -->
<button class="nav-link terminal-toggle" id="terminal-toggle-btn">⌨</button>

<!-- Slide-out panel (hidden by default, overlays bottom of viewport) -->
<div id="terminal-panel" class="terminal-panel terminal-panel--closed">
  <div class="terminal-panel-drag" id="terminal-drag"></div>
  <div class="terminal-output" id="terminal-output"></div>
  <div class="terminal-status" id="terminal-status"></div>
  <div class="terminal-input-line">
    <span class="terminal-prompt" id="terminal-prompt">[UNMOUNTED] ▶</span>
    <input class="terminal-input" type="text" id="terminal-input"
           placeholder="type a command…" autocomplete="off" spellcheck="false">
  </div>
</div>
```

CSS for the panel uses `position: fixed; bottom: 0;` with a transition on height. The drag handle uses a `mousedown`/`mousemove` listener to resize.

### Comparison

| Aspect | Option A: Admin Tab | Option B: Slide-Out Panel |
|--------|-------------------|-------------------------|
| **Multitasking** | Cannot see browse content while using terminal — must switch back and forth | Browse and run commands simultaneously — look at titles while syncing |
| **Screen real estate** | Terminal gets the full viewport — spacious output area, comfortable for long command output | Terminal shares the viewport — output area is smaller, especially on shorter displays |
| **Complexity** | Simple — uses existing view-switching pattern, no resize/drag logic | Moderately more complex — needs drag-to-resize, CSS transitions, viewport splitting |
| **Consistency** | Matches existing UI patterns (Actresses, Collections, Queues are all full-page views) | Introduces a new UI pattern not used elsewhere in the app |
| **Discoverability** | "Admin" label in the nav is clear and visible | Icon button is subtler; first-time discovery is less obvious |
| **Long-running commands** | Must stay on Admin view to watch progress (or navigate away and lose sight of it) | Can watch sync progress in the panel while browsing titles above |
| **Mobile/narrow screens** | Works fine — terminal just fills the narrow viewport | Panel may be cramped on small screens; drag-to-resize is awkward on touch |
| **Implementation effort** | ~30 min less frontend work (no drag/resize logic) | Drag handle + resize + CSS transitions add modest complexity |

**When Option A is better:** You think of admin as a separate activity — "now I'm going to do admin work" — and want the full screen for it. Simpler to build, consistent with the rest of the UI.

**When Option B is better:** You want to browse while running commands — kick off a `sync all`, watch the progress bar tick up in the panel while scrolling through titles above. Feels more like having a terminal integrated into your workflow.

Both are viable. The server-side implementation is identical either way — only the CSS/JS for showing and positioning the terminal container differs.

### 5.3 Shared Components (Both Options)

Regardless of layout choice, the terminal container has three internal zones:

**Output area** (`terminal-output`): A scrollable `<div>` that accumulates output lines as `<div class="terminal-line">` elements. Auto-scrolls to bottom on new output. Each line is plain HTML — no terminal emulation. ANSI colors are converted to `<span>` elements with CSS classes (see §6).

Commands entered by the user are echoed in the output area prefixed with `>` in a distinct style, creating a visible command history. The output area persists across view switches (Option A) or panel open/close (Option B) — your history is never lost.

**Status area** (`terminal-status`): A collapsible zone between the output and input, used for transient feedback:

- **Spinner:** When a `spinner-start` message arrives, shows a CSS-animated spinner icon + label. Updated on `spinner-update`, hidden on `spinner-stop`.
- **Progress bar:** When `progress-start` arrives, renders a real HTML `<div>`-based progress bar with label, current/total count, and detail item name. Updates in real time on `progress-update`. Hidden on `progress-stop`.
- **Pick list:** When a `pick` message arrives, renders items as a vertical list of clickable rows (styled like the existing dropdown menus). Clicking an item sends `pick-response` with the index. A "Cancel" option sends index -1.

When no activity is happening, the status area collapses to zero height.

**Input line** (`terminal-input-line`): Pinned at the bottom. The prompt text (e.g., `[MOUNT → a] ▶`) is a non-editable `<span>` before the text input, updated via `prompt` messages from the server. Pressing Enter sends the command. The input is disabled and grayed out while a command is executing. Command history is stored in `localStorage` and navigable with up/down arrow keys.

### WebSocket Lifecycle

The WebSocket connection is opened on first use (first Admin tab click, or first panel open) and kept alive as long as the page is open — even when navigating to other views or closing the panel. This avoids reconnect latency. If the connection drops, a "disconnected" indicator appears and it auto-reconnects on next interaction.

### No xterm.js

A full terminal emulator (xterm.js) is overkill for this use case. The organizer shell is not a general-purpose terminal — it's a structured command dispatcher with well-defined output types (text lines, spinners, progress bars, pick lists). A custom UI can render each of these natively as HTML/CSS components, which looks better and is simpler than piping raw ANSI through a terminal emulator.

xterm.js would also require a PTY bridge on the server side, adding complexity for no benefit. The `CommandIO` abstraction already gives us structured output — we should use it.

---

## 6. ANSI-to-HTML Conversion

Commands use a small set of ANSI codes (defined in `Ansi.java`): bold, dim, color foregrounds (red, green, cyan, yellow, white), and reset. The frontend needs a simple converter:

```javascript
function ansiToHtml(text) {
    return text
        .replace(/\033\[0m/g, '</span>')
        .replace(/\033\[1m/g, '<span class="ansi-bold">')
        .replace(/\033\[2m/g, '<span class="ansi-dim">')
        .replace(/\033\[31m/g, '<span class="ansi-red">')
        .replace(/\033\[32m/g, '<span class="ansi-green">')
        .replace(/\033\[36m/g, '<span class="ansi-cyan">')
        .replace(/\033\[33m/g, '<span class="ansi-yellow">')
        .replace(/\033\[37m/g, '<span class="ansi-white">')
        // ... handle combined codes like \033[1;32m
        ;
}
```

CSS classes map to the dark-theme color palette already used by the web UI. This is ~30 lines of JS — no library needed.

---

## 7. Tab Completion

The terminal shell has tab completion via JLine3. The web terminal can offer the same with a lightweight approach:

### Server-Side

A new endpoint (or WebSocket message type) returns the available commands:

```json
{ "type": "completions", "commands": ["mount", "unmount", "volumes", "sync all", "sync queue", ...] }
```

Sent once on WebSocket connect. Updated if the command list could change (it doesn't currently — commands are static at startup).

### Client-Side

When the user presses Tab in the input field:

1. Match the current input prefix against the known command list
2. If one match → auto-complete it
3. If multiple matches → show them as a hint below the input
4. If no matches → do nothing

This covers command-name completion. Argument completion (e.g., volume IDs after `mount`) can be added later by sending richer completion metadata.

---

## 8. New Components Summary

| Component | Package | Responsibility |
|-----------|---------|----------------|
| `CommandDispatcher` | `shell` | Extracted dispatch logic — shared by shell and web terminal |
| `WebTerminalHandler` | `web` | WebSocket endpoint: receives commands, manages IO lifecycle |
| `WebSocketCommandIO` | `shell.io` | `CommandIO` impl that sends output as WebSocket JSON messages |
| `WebSocketSpinner` | `shell.io` | Sends spinner start/update/stop messages over WebSocket |
| `WebSocketProgress` | `shell.io` | Sends progress start/update/stop messages over WebSocket |

Frontend additions (all in existing files — no new HTML pages):

| File | Addition |
|------|----------|
| `index.html` | Terminal panel markup, toggle button in header |
| `style.css` | Terminal panel styles, ANSI color classes, progress bar, pick list |
| `app.js` | WebSocket client, terminal panel logic, ANSI-to-HTML, tab completion |

---

## 9. What Changes, What Doesn't

### Changes (small, isolated)

- **`OrganizerShell`**: Extract `dispatch()` into `CommandDispatcher`. The shell's `run()` loop calls `dispatcher.dispatch()` instead of its private method. Behavior-preserving refactor.
- **`WebServer`**: Add WebSocket route registration. New constructor parameter for `CommandDispatcher` + `SessionContext`.
- **`Application.java`**: Wire `CommandDispatcher`, pass it to both `OrganizerShell` and `WebServer`.
- **Frontend files**: Add terminal panel UI.

### Does NOT change

- **`Command` interface**: Untouched. No command knows about WebSockets.
- **`CommandIO` interface**: Untouched. New implementation, no interface changes.
- **`SessionContext`**: Untouched. Shared as-is.
- **Every existing command**: Untouched. They already talk through `CommandIO`.
- **Existing web API routes**: Untouched.
- **Tests**: Existing tests continue to work. New tests cover `CommandDispatcher`, `WebSocketCommandIO`, `WebTerminalHandler`.

---

## 10. Phased Implementation

### Phase 1 — MVP: Text Commands

- Extract `CommandDispatcher` from `OrganizerShell`
- `WebSocketCommandIO` with `println()` only (no spinner/progress/pick)
- `WebTerminalHandler` WebSocket endpoint
- Frontend: terminal panel with output area and input line
- Commands that only use `println()` work fully (help, actresses, favorites, etc.)
- Spinner/progress commands (mount, sync) work but show no visual feedback — just the final output lines

### Phase 2 — Rich Feedback

- Spinner rendering (CSS animation + label)
- Progress bar rendering (HTML progress element)
- Prompt updates reflecting session state
- ANSI-to-HTML conversion for colored output

### Phase 3 — Interactive Commands

- Pick list rendering and response handling
- `CountDownLatch`-based blocking in `WebSocketCommandIO.pick()`
- Tab completion (command names)

### Phase 4 — Polish

- Command history in `localStorage` (up/down arrows)
- Keyboard shortcut to toggle terminal (e.g., `` Ctrl+` ``)
- Auto-reconnect WebSocket on connection drop
- If Option B (slide-out panel): drag-to-resize handle, remember panel height/open state across page reloads

---

## 11. Risks and Considerations

| Risk | Mitigation |
|------|-----------|
| Concurrent commands from terminal + web could conflict | Single-user; in practice you won't do this. If needed, a simple lock on `CommandDispatcher` serializes execution |
| WebSocket disconnect mid-command | Command continues to completion server-side (output is lost). Reconnecting shows current prompt state. No cleanup needed — commands are idempotent or database-backed |
| `pick()` blocks command thread waiting for browser response | Fine for single-user. If the browser disconnects, the `CountDownLatch` times out after N seconds and returns `Optional.empty()` |
| ANSI codes more complex than expected | The codebase uses a small, well-defined set from `Ansi.java`. Converter only needs to handle those specific codes |
| Large command output (sync printing hundreds of lines) | Browser DOM handles thousands of divs fine. Could cap at N lines with a "show more" if needed, but unlikely to matter |
| `shutdown` command from web kills the whole app | Correct and intentional — same as from the terminal. The browser shows a disconnected state |
