// Logs tool — polls /api/logs/tail while visible and appends new content.
// First load returns the recent tail (~32KB). Poll cadence: 3s.

const view         = document.getElementById('tools-logs-view');
const output       = document.getElementById('logs-output');
const statusEl     = document.getElementById('logs-status');
const autoscrollCb = document.getElementById('logs-autoscroll');
const pausedCb     = document.getElementById('logs-paused');
const clearBtn     = document.getElementById('logs-clear-btn');
const jumpBtn      = document.getElementById('logs-jump-btn');

const POLL_MS = 3000;
const MAX_LINES = 5000;

let offset = null;
let timer  = null;
let visible = false;
let inFlight = false;
let pendingLineHead = '';   // holds the tail fragment with no trailing newline

export function showLogsView() {
  view.style.display = 'flex';
  visible = true;
  offset = null;
  output.innerHTML = '';
  pendingLineHead = '';
  setStatus('Loading…');
  updateJumpBtn();
  poll();
  if (!timer) timer = setInterval(poll, POLL_MS);
}

export function hideLogsView() {
  view.style.display = 'none';
  visible = false;
  if (timer) { clearInterval(timer); timer = null; }
}

async function poll() {
  if (!visible || inFlight || pausedCb.checked) return;
  inFlight = true;
  try {
    const url = offset == null ? '/api/logs/tail' : `/api/logs/tail?since=${offset}`;
    const res = await fetch(url);
    if (!res.ok) { setStatus(`HTTP ${res.status}`); return; }
    const data = await res.json();
    if (data.missing) {
      setStatus('Log file not found');
      offset = 0;
      return;
    }
    if (data.rotated) {
      output.innerHTML = '';
      pendingLineHead = '';
      setStatus('Log rotated — reloaded tail');
    }
    if (data.content) appendContent(data.content);
    offset = data.offset;
    setStatus(data.truncated ? 'Catching up…' : `Tailing · ${formatSize(data.size)}`);
    if (data.truncated) setTimeout(poll, 50);
  } catch (err) {
    console.error('logs poll failed', err);
    setStatus('Poll error');
  } finally {
    inFlight = false;
    updateJumpBtn();
  }
}

function appendContent(text) {
  const stick = isAtBottom();
  const buffer = pendingLineHead + text;
  const lines = buffer.split('\n');
  // Last element is either empty (trailing newline) or a partial line we hold over.
  pendingLineHead = lines.pop();

  const frag = document.createDocumentFragment();
  for (const line of lines) {
    frag.appendChild(renderLine(line));
  }
  output.appendChild(frag);

  // Trim to MAX_LINES from the top.
  while (output.childElementCount > MAX_LINES) {
    output.firstElementChild.remove();
  }

  if (stick && autoscrollCb.checked) {
    output.scrollTop = output.scrollHeight;
  }
}

// Logback pattern: yyyy-MM-dd HH:mm:ss [thread] LEVEL logger - msg
const LINE_RE = /^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s+(\[[^\]]*\])\s+(TRACE|DEBUG|INFO|WARN|ERROR)\s+(\S+)\s+-\s?(.*)$/;

function renderLine(line) {
  const div = document.createElement('div');
  div.className = 'logs-line';
  const m = LINE_RE.exec(line);
  if (!m) {
    // Continuation line (stack trace, multiline message) — just render as-is.
    div.classList.add('logs-line-cont');
    div.textContent = line;
    return div;
  }
  const [, ts, thread, level, logger, msg] = m;
  div.classList.add('logs-level-' + level.toLowerCase());
  div.innerHTML =
      `<span class="logs-ts">${esc(ts)}</span> ` +
      `<span class="logs-thread">${esc(thread)}</span> ` +
      `<span class="logs-level logs-level-tag-${level.toLowerCase()}">${esc(level)}</span> ` +
      `<span class="logs-logger">${esc(logger)}</span> ` +
      `<span class="logs-msg">${esc(msg)}</span>`;
  return div;
}

function esc(s) {
  return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
}

function isAtBottom() {
  return output.scrollTop + output.clientHeight >= output.scrollHeight - 30;
}

function scrollToBottom() {
  output.scrollTop = output.scrollHeight;
  updateJumpBtn();
}

function updateJumpBtn() {
  if (!jumpBtn) return;
  jumpBtn.style.display = isAtBottom() ? 'none' : 'flex';
}

function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

function setStatus(msg) {
  statusEl.textContent = msg;
}

clearBtn.addEventListener('click', () => {
  output.innerHTML = '';
  pendingLineHead = '';
});

pausedCb.addEventListener('change', () => {
  if (!pausedCb.checked) poll();
});

output.addEventListener('scroll', updateJumpBtn);
jumpBtn?.addEventListener('click', scrollToBottom);
