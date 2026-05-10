/* ─────────────────────────────────────────────────────────────────────
   Wave 3 — Logs viewer (workbench mode)
   Spec: spec/DESIGN_SYSTEM_PAGES.md (workbench surfaces sweep)
   Live tail of logs/organizer3.log via /api/logs/tail incremental
   polling. Pause/resume, clear-view, level filter, follow toggle.
   ───────────────────────────────────────────────────────────────────── */

const POLL_MS = 1500;
const MAX_BYTES_IN_VIEW = 512 * 1024; // trim oldest content past this

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  }[c]));
}

async function fetchJson(url, fallback = null) {
  try {
    const r = await fetch(url, { cache: 'no-cache' });
    if (!r.ok) return fallback;
    return await r.json();
  } catch (e) { return fallback; }
}

// Heuristic line classifier: timestamps + bracket levels
const LEVEL_RE = /\b(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\b/;
function classifyLine(line) {
  const m = line.match(LEVEL_RE);
  if (!m) return '';
  return m[1].toLowerCase();
}

function formatLine(raw) {
  const lvl = classifyLine(raw);
  const cls = (lvl === 'fatal') ? 'error' : lvl;
  return `<span class="line ${cls}">${escapeHtml(raw)}</span>`;
}

export async function mountLogs(rootEl) {
  rootEl.innerHTML = `
    <div class="wb-page">
      <h1 class="wb-page-title">Logs</h1>
      <div class="wb-page-subtitle">Live tail of <code>logs/organizer3.log</code>. Polls every ${POLL_MS}ms.</div>

      <div class="filter-bar">
        <button class="btn sm" id="btn-pause">Pause</button>
        <button class="btn sm" id="btn-clear">Clear view</button>
        <div class="filter-group" style="margin-left:6px">
          <span class="filter-label">Level:</span>
          <span class="chip on" data-lvl="">All</span>
          <span class="chip" data-lvl="info">Info+</span>
          <span class="chip" data-lvl="warn">Warn+</span>
          <span class="chip" data-lvl="error">Error</span>
        </div>
        <label style="display:inline-flex;align-items:center;gap:5px;font-size:12px;color:var(--text-dim);margin-left:12px">
          <input type="checkbox" id="cb-follow" checked> Follow tail
        </label>
        <div class="filter-spacer"></div>
        <div class="filter-meta" id="result-meta">connecting…</div>
      </div>

      <div class="logtail" id="logtail"></div>
    </div>
  `;

  const tail      = rootEl.querySelector('#logtail');
  const meta      = rootEl.querySelector('#result-meta');
  const btnPause  = rootEl.querySelector('#btn-pause');
  const btnClear  = rootEl.querySelector('#btn-clear');
  const cbFollow  = rootEl.querySelector('#cb-follow');
  const lvlChips  = rootEl.querySelectorAll('.chip[data-lvl]');

  const state = {
    offset: null,    // last byte offset returned by server; null on first request
    size: 0,
    paused: false,
    timer: null,
    minLevel: '',    // '' = all
    bytesInView: 0,
  };

  const LEVEL_RANK = { '': 0, debug: 1, info: 2, warn: 3, error: 4, fatal: 4 };

  const acceptLine = (line) => {
    if (!state.minLevel) return true;
    const lvl = classifyLine(line);
    return (LEVEL_RANK[lvl] || 0) >= (LEVEL_RANK[state.minLevel] || 0);
  };

  const trimIfHuge = () => {
    if (state.bytesInView <= MAX_BYTES_IN_VIEW) return;
    // Drop the first ~1/4 of children
    const drop = Math.floor(tail.children.length / 4);
    let removed = 0;
    for (let i = 0; i < drop && tail.firstChild; i++) {
      removed += (tail.firstChild.textContent || '').length;
      tail.removeChild(tail.firstChild);
    }
    state.bytesInView -= removed;
  };

  const appendChunk = (text) => {
    if (!text) return;
    const lines = text.split('\n');
    // If last char wasn't newline, the last entry is partial — render anyway,
    // server will continue from cursor on next poll.
    const wasAtBottom = (tail.scrollHeight - tail.scrollTop - tail.clientHeight) < 24;

    let html = '';
    for (const ln of lines) {
      if (!ln) continue;
      if (!acceptLine(ln)) continue;
      html += formatLine(ln);
    }
    if (!html) return;

    tail.insertAdjacentHTML('beforeend', html);
    state.bytesInView += text.length;
    trimIfHuge();

    if (cbFollow.checked && wasAtBottom) {
      tail.scrollTop = tail.scrollHeight;
    }
  };

  const poll = async () => {
    if (state.paused) return;
    const url = state.offset == null ? '/api/logs/tail' : `/api/logs/tail?since=${state.offset}`;
    const r = await fetchJson(url, null);
    if (!r) {
      meta.textContent = 'fetch failed';
      return;
    }
    if (r.missing) {
      tail.innerHTML = `<div class="logtail-empty">Log file not found.</div>`;
      meta.textContent = '';
      return;
    }
    if (r.rotated) {
      tail.innerHTML = `<div class="logtail-empty">— log rotated —</div>`;
      state.bytesInView = 0;
    }
    appendChunk(r.content);
    state.offset = r.offset;
    state.size   = r.size;
    meta.textContent = `${(r.size / 1024).toFixed(1)} KB · cursor ${r.offset}${state.paused ? ' · paused' : ''}`;
  };

  // Wire controls
  btnPause.addEventListener('click', () => {
    state.paused = !state.paused;
    btnPause.textContent = state.paused ? 'Resume' : 'Pause';
    if (!state.paused) poll();
  });
  btnClear.addEventListener('click', () => {
    tail.innerHTML = '';
    state.bytesInView = 0;
  });
  lvlChips.forEach(c => c.addEventListener('click', () => {
    lvlChips.forEach(x => x.classList.remove('on'));
    c.classList.add('on');
    state.minLevel = c.dataset.lvl || '';
    // Reapply filter to existing lines
    const keep = [];
    tail.querySelectorAll('.line').forEach(el => {
      const lvl = el.className.replace('line ', '').trim();
      const rank = (LEVEL_RANK[lvl] || 0);
      if (!state.minLevel || rank >= LEVEL_RANK[state.minLevel]) {
        keep.push(el.outerHTML);
      }
    });
    tail.innerHTML = keep.join('');
  }));

  await poll();
  state.timer = setInterval(poll, POLL_MS);

  // Note: in v2 navigation away unmounts the page (multi-page architecture),
  // so the interval is collected with the page. No teardown handle needed.
}
