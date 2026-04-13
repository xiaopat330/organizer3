// ── Web terminal slide-out panel ──────────────────────────────────────────────
//
// Protocol (server → browser):
//   {type:"output",    text:"...", ansi:"..."}   — output line (ansi optional)
//   {type:"spinner-start",  label:"..."}
//   {type:"spinner-update", label:"..."}
//   {type:"spinner-stop"}
//   {type:"progress-start", label:"...", total:N}
//   {type:"progress-update", current:N, total:N, detail:"..."}
//   {type:"progress-stop"}
//   {type:"pick",    items:[...]}
//   {type:"prompt",  text:"..."}
//   {type:"ready"}
//
// Protocol (browser → server):
//   {type:"command",       text:"mount a"}
//   {type:"pick-response", index:2}

const SPINNER_FRAMES = ['⠋','⠙','⠹','⠸','⠼','⠴','⠦','⠧','⠇','⠏'];
const RECONNECT_DELAY_MS = 3000;
const DEFAULT_HEIGHT_PX  = 320;
const MIN_HEIGHT_PX      = 120;
const STORAGE_KEY_HEIGHT = 'terminal-panel-height';
const STORAGE_KEY_OPEN   = 'terminal-panel-open';

let ws = null;
let wsReady = false;
let spinnerInterval = null;
let spinnerFrameIdx = 0;
let historyBuf = [];
let historyPos  = -1;
let pendingInput = null; // command typed before WS was ready
let reconnectTimer = null;

// DOM refs — populated in init()
let panel, output, statusArea, promptEl, inputEl, dragHandle;

// ── Public ─────────────────────────────────────────────────────────────────────

export function initTerminal() {
  panel      = document.getElementById('terminal-panel');
  output     = document.getElementById('terminal-output');
  statusArea = document.getElementById('terminal-status');
  promptEl   = document.getElementById('terminal-prompt');
  inputEl    = document.getElementById('terminal-input');
  dragHandle = document.getElementById('terminal-drag-handle');

  // Restore saved height
  const savedH = parseInt(localStorage.getItem(STORAGE_KEY_HEIGHT), 10);
  if (savedH && savedH >= MIN_HEIGHT_PX) panel.style.setProperty('--terminal-h', savedH + 'px');

  // Toggle button
  document.getElementById('terminal-toggle-btn').addEventListener('click', togglePanel);

  // Input: Enter sends command, up/down navigates history
  inputEl.addEventListener('keydown', onInputKeydown);

  // Drag-to-resize
  dragHandle.addEventListener('mousedown', onDragStart);

  // Open if previously open
  if (localStorage.getItem(STORAGE_KEY_OPEN) === 'true') openPanel();
}

export function togglePanel() {
  if (panel.classList.contains('terminal-open')) {
    closePanel();
  } else {
    openPanel();
  }
}

// ── Panel open / close ─────────────────────────────────────────────────────────

function openPanel() {
  const h = parseInt(localStorage.getItem(STORAGE_KEY_HEIGHT), 10) || DEFAULT_HEIGHT_PX;
  panel.style.height = h + 'px';
  panel.classList.add('terminal-open');
  localStorage.setItem(STORAGE_KEY_OPEN, 'true');
  ensureConnected();
  if (wsReady) inputEl.focus();
}

function closePanel() {
  panel.style.height = '0';
  panel.classList.remove('terminal-open');
  localStorage.setItem(STORAGE_KEY_OPEN, 'false');
}

// ── WebSocket ──────────────────────────────────────────────────────────────────

function ensureConnected() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
  connect();
}

function connect() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  ws = new WebSocket(`${proto}://${location.host}/ws/terminal`);

  ws.onopen = () => {
    clearTimeout(reconnectTimer);
    clearDisconnected();
  };

  ws.onmessage = (e) => {
    let msg;
    try { msg = JSON.parse(e.data); } catch { return; }
    handleMessage(msg);
  };

  ws.onclose = () => {
    wsReady = false;
    setInputEnabled(false);
    showDisconnected();
    reconnectTimer = setTimeout(connect, RECONNECT_DELAY_MS);
  };

  ws.onerror = () => {
    // onclose fires after onerror, handles reconnect
  };
}

function send(obj) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

// ── Message handling ───────────────────────────────────────────────────────────

function handleMessage(msg) {
  switch (msg.type) {
    case 'output':
      appendOutput(msg.ansi ? ansiToHtml(msg.ansi) : escapeHtml(msg.text), false);
      break;

    case 'spinner-start':
      startSpinner(msg.label);
      break;
    case 'spinner-update':
      updateSpinner(msg.label);
      break;
    case 'spinner-stop':
      stopSpinner();
      break;

    case 'progress-start':
      startProgress(msg.label, msg.total);
      break;
    case 'progress-update':
      updateProgress(msg.current, msg.total, msg.detail);
      break;
    case 'progress-stop':
      stopProgress();
      break;

    case 'pick':
      showPickList(msg.items);
      break;

    case 'prompt':
      updatePrompt(msg.text);
      break;

    case 'ready':
      wsReady = true;
      setInputEnabled(true);
      if (panel.classList.contains('terminal-open')) inputEl.focus();
      break;
  }
}

// ── Output ─────────────────────────────────────────────────────────────────────

function appendOutput(htmlContent, isCmd) {
  const line = document.createElement('div');
  line.className = isCmd ? 'terminal-line terminal-cmd' : 'terminal-line';
  line.innerHTML = htmlContent;
  output.appendChild(line);
  output.scrollTop = output.scrollHeight;
}

// ── Spinner ────────────────────────────────────────────────────────────────────

function startSpinner(label) {
  stopSpinner();
  spinnerFrameIdx = 0;
  const el = document.createElement('div');
  el.className = 'terminal-spinner';
  el.id = 'terminal-spinner-el';
  el.innerHTML = `<span class="terminal-spinner-frame">${SPINNER_FRAMES[0]}</span><span class="terminal-spinner-label">${escapeHtml(label)}</span>`;
  statusArea.appendChild(el);
  spinnerInterval = setInterval(() => {
    spinnerFrameIdx = (spinnerFrameIdx + 1) % SPINNER_FRAMES.length;
    const frame = document.querySelector('#terminal-spinner-el .terminal-spinner-frame');
    if (frame) frame.textContent = SPINNER_FRAMES[spinnerFrameIdx];
  }, 80);
}

function updateSpinner(label) {
  const labelEl = document.querySelector('#terminal-spinner-el .terminal-spinner-label');
  if (labelEl) labelEl.textContent = label;
}

function stopSpinner() {
  clearInterval(spinnerInterval);
  spinnerInterval = null;
  const el = document.getElementById('terminal-spinner-el');
  if (el) el.remove();
}

// ── Progress ───────────────────────────────────────────────────────────────────

function startProgress(label, total) {
  stopProgress();
  const el = document.createElement('div');
  el.className = 'terminal-progress';
  el.id = 'terminal-progress-el';
  el.innerHTML = `
    <span class="terminal-progress-label">${escapeHtml(label)}</span>
    <div class="terminal-progress-bar-wrap">
      <div class="terminal-progress-bar-fill" style="width:0%"></div>
    </div>
    <span class="terminal-progress-fraction">0/${total}</span>`;
  statusArea.appendChild(el);
}

function updateProgress(current, total, detail) {
  const el = document.getElementById('terminal-progress-el');
  if (!el) return;
  const pct = total > 0 ? Math.round(current * 100 / total) : 0;
  el.querySelector('.terminal-progress-bar-fill').style.width = pct + '%';
  el.querySelector('.terminal-progress-fraction').textContent = `${current}/${total}`;
  if (detail != null) {
    el.querySelector('.terminal-progress-label').textContent = detail;
  }
}

function stopProgress() {
  const el = document.getElementById('terminal-progress-el');
  if (el) el.remove();
}

// ── Pick list ──────────────────────────────────────────────────────────────────

function showPickList(items) {
  clearStatus();
  const ul = document.createElement('ul');
  ul.className = 'terminal-pick-list';
  ul.id = 'terminal-pick-list';

  items.forEach((item, i) => {
    const li = document.createElement('li');
    li.className = 'terminal-pick-item';
    li.innerHTML = ansiToHtml(item);
    li.addEventListener('click', () => {
      clearStatus();
      send({ type: 'pick-response', index: i });
    });
    ul.appendChild(li);
  });

  const cancel = document.createElement('li');
  cancel.className = 'terminal-pick-item terminal-pick-cancel';
  cancel.textContent = '(cancel)';
  cancel.addEventListener('click', () => {
    clearStatus();
    send({ type: 'pick-response', index: -1 });
  });
  ul.appendChild(cancel);

  statusArea.appendChild(ul);
}

function clearStatus() {
  stopSpinner();
  stopProgress();
  const pick = document.getElementById('terminal-pick-list');
  if (pick) pick.remove();
}

// ── Prompt ─────────────────────────────────────────────────────────────────────

function updatePrompt(text) {
  promptEl.textContent = text.trim();
  if (text.includes('UNMOUNTED')) {
    promptEl.classList.add('terminal-unmounted');
  } else {
    promptEl.classList.remove('terminal-unmounted');
  }
}

// ── Input ──────────────────────────────────────────────────────────────────────

function onInputKeydown(e) {
  if (e.key === 'Enter') {
    e.preventDefault();
    const text = inputEl.value.trim();
    inputEl.value = '';
    if (text) {
      historyBuf.unshift(text);
      if (historyBuf.length > 200) historyBuf.pop();
      historyPos = -1;
    }
    // Echo the command in the output area
    appendOutput(escapeHtml(text), true);
    setInputEnabled(false);
    send({ type: 'command', text });
  } else if (e.key === 'ArrowUp') {
    e.preventDefault();
    if (historyPos < historyBuf.length - 1) {
      historyPos++;
      inputEl.value = historyBuf[historyPos];
    }
  } else if (e.key === 'ArrowDown') {
    e.preventDefault();
    if (historyPos > 0) {
      historyPos--;
      inputEl.value = historyBuf[historyPos];
    } else {
      historyPos = -1;
      inputEl.value = '';
    }
  }
}

function setInputEnabled(enabled) {
  inputEl.disabled = !enabled;
  if (enabled && panel.classList.contains('terminal-open')) inputEl.focus();
}

// ── Disconnected indicator ─────────────────────────────────────────────────────

function showDisconnected() {
  let el = document.getElementById('terminal-disconnected');
  if (!el) {
    el = document.createElement('div');
    el.id = 'terminal-disconnected';
    el.className = 'terminal-disconnected';
    el.textContent = 'disconnected — reconnecting…';
    statusArea.prepend(el);
  }
}

function clearDisconnected() {
  const el = document.getElementById('terminal-disconnected');
  if (el) el.remove();
}

// ── Drag-to-resize ─────────────────────────────────────────────────────────────

function onDragStart(e) {
  e.preventDefault();
  const startY   = e.clientY;
  const startH   = panel.offsetHeight;

  function onMove(e) {
    const delta = startY - e.clientY;
    const newH  = Math.max(MIN_HEIGHT_PX, startH + delta);
    panel.style.height = newH + 'px';
    localStorage.setItem(STORAGE_KEY_HEIGHT, newH);
  }

  function onUp() {
    document.removeEventListener('mousemove', onMove);
    document.removeEventListener('mouseup', onUp);
  }

  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', onUp);
}

// ── ANSI → HTML ────────────────────────────────────────────────────────────────

function ansiToHtml(text) {
  const escaped = escapeHtml(text);
  return escaped
    .replace(/\033\[0m/g,    '</span>')
    .replace(/\033\[1m/g,    '<span class="ansi-bold">')
    .replace(/\033\[2m/g,    '<span class="ansi-dim">')
    .replace(/\033\[31m/g,   '<span class="ansi-red">')
    .replace(/\033\[91m/g,   '<span class="ansi-red">')
    .replace(/\033\[32m/g,   '<span class="ansi-green">')
    .replace(/\033\[92m/g,   '<span class="ansi-green">')
    .replace(/\033\[33m/g,   '<span class="ansi-yellow">')
    .replace(/\033\[93m/g,   '<span class="ansi-yellow">')
    .replace(/\033\[36m/g,   '<span class="ansi-cyan">')
    .replace(/\033\[96m/g,   '<span class="ansi-cyan">')
    .replace(/\033\[37m/g,   '<span class="ansi-white">')
    .replace(/\033\[97m/g,   '<span class="ansi-white">')
    .replace(/\033\[1;31m/g, '<span class="ansi-bold ansi-red">')
    .replace(/\033\[1;32m/g, '<span class="ansi-bold ansi-green">')
    .replace(/\033\[1;33m/g, '<span class="ansi-bold ansi-yellow">')
    .replace(/\033\[1;36m/g, '<span class="ansi-bold ansi-cyan">');
}

function escapeHtml(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}
