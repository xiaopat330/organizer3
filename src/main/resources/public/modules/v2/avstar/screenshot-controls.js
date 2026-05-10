/* ─────────────────────────────────────────────────────────────────────
   v2/avstar/screenshot-controls.js
   Direct port of legacy av-screenshot-controls.js.
   API: mount(containerEl, actressId, pendingVideoCount) / unmount()

   States (spec §5.1):
     all-done  — "Screenshots ✓" disabled
     idle      — "Generate screenshots (N pending)"
     running   — progress bar + Pause + Stop
     paused    — progress bar (paused style) + Resume + Stop
     (sub-state) streamGated — running state + "paused — video playing" sub-label
   ───────────────────────────────────────────────────────────────────── */

const POLL_MS = 2000;

let _container = null;
let _actressId = null;
let _pendingVideoCount = null;
let _totalVideoCount = null;
let _pollTimer = null;
let _mountId = 0;

/**
 * Mount screenshot controls into containerEl.
 * pendingVideoCount: videos lacking screenshots (pass null to auto-fetch).
 */
export function mount(containerEl, actressId, pendingVideoCount = null) {
  unmount();
  _container = containerEl;
  _actressId = actressId;
  _pendingVideoCount = pendingVideoCount;
  const id = ++_mountId;
  if (_pendingVideoCount === null) {
    _fetchPendingCount(id).then(() => _fetchAndRender(id));
  } else {
    _fetchAndRender(id);
  }
}

async function _fetchPendingCount(id) {
  try {
    const res = await fetch(`/api/av/actresses/${_actressId}/videos`);
    if (_mountId !== id || !_container) return;
    if (!res.ok) return;
    const videos = await res.json();
    if (_mountId !== id || !_container) return;
    _totalVideoCount = videos.length;
    _pendingVideoCount = videos.filter(v => !v.screenshotCount).length;
  } catch { /* leave counts null; labels degrade gracefully */ }
}

/** Safe to call when not mounted. */
export function unmount() {
  if (_pollTimer !== null) {
    clearInterval(_pollTimer);
    _pollTimer = null;
  }
  _container = null;
  _actressId = null;
  _pendingVideoCount = null;
  _totalVideoCount = null;
}

// ── Fetch / poll ──────────────────────────────────────────────────────────

async function _fetchAndRender(id) {
  if (_mountId !== id || !_container) return;
  try {
    const [pRes, wRes] = await Promise.all([
      fetch(`/api/av/actresses/${_actressId}/screenshots/progress`),
      fetch('/api/av/screenshot-queue/state'),
    ]);
    if (_mountId !== id || !_container) return;
    if (!pRes.ok || !wRes.ok) return;
    const progress = await pRes.json();
    const worker   = await wRes.json();
    if (_mountId !== id || !_container) return;
    _render(progress, worker);
    if (_isActive(progress) && _pollTimer === null) {
      _pollTimer = setInterval(() => _tick(id), POLL_MS);
    }
  } catch { /* network error; retried on next tick if polling */ }
}

async function _tick(id) {
  if (_mountId !== id || !_container) {
    clearInterval(_pollTimer);
    _pollTimer = null;
    return;
  }
  try {
    const [pRes, wRes] = await Promise.all([
      fetch(`/api/av/actresses/${_actressId}/screenshots/progress`),
      fetch('/api/av/screenshot-queue/state'),
    ]);
    if (_mountId !== id || !_container) return;
    if (!pRes.ok || !wRes.ok) return;
    const progress = await pRes.json();
    const worker   = await wRes.json();
    if (_mountId !== id || !_container) return;
    _render(progress, worker);
    if (!_isActive(progress)) {
      clearInterval(_pollTimer);
      _pollTimer = null;
      if (progress.pending + progress.inProgress + progress.paused === 0) {
        window.dispatchEvent(new CustomEvent('av-screenshots-queue-done', {
          detail: { actressId: _actressId },
        }));
      }
    }
  } catch { /* ignore; next tick retries */ }
}

function _isActive(p) {
  return p.pending + p.inProgress + p.paused > 0;
}

// ── State renderers ────────────────────────────────────────────────────────

function _render(progress, worker) {
  if (!_container) return;
  const { pending, inProgress, paused, done, total, currentVideoId } = progress;

  if (pending + inProgress > 0) {
    const streamGated = worker.streamActive
      && currentVideoId != null
      && worker.currentVideoId === currentVideoId;
    _renderRunning(progress, streamGated);
    return;
  }

  if (paused > 0) {
    _renderPaused(progress);
    return;
  }

  const allDoneFromQueue = total > 0 && done === total;
  if (_pendingVideoCount === 0 || allDoneFromQueue) {
    _renderAllDone(allDoneFromQueue ? total : _totalVideoCount);
  } else {
    _renderIdle();
  }
}

function _renderAllDone(totalForLabel) {
  const n = totalForLabel != null && totalForLabel > 0
    ? totalForLabel
    : _totalVideoCount;
  const label = n != null && n > 0
    ? `Screenshots ✓ ${n}/${n}`
    : 'Screenshots ✓';
  _container.innerHTML = `
    <div class="av-ss-btn-row">
      <button class="av-ss-btn av-ss-done" disabled>${label}</button>
      <button class="av-ss-action av-ss-reset" title="Delete all screenshots for this actress and start over">Reset</button>
    </div>`;
  _container.querySelector('.av-ss-reset').addEventListener('click', () => _resetActress());
}

function _renderIdle() {
  const label = _pendingVideoCount != null
    ? `Generate screenshots (${_pendingVideoCount} pending)`
    : 'Generate screenshots';
  const hasExisting = _totalVideoCount != null && _pendingVideoCount != null
    && _totalVideoCount > _pendingVideoCount;
  const resetBtn = hasExisting
    ? `<button class="av-ss-action av-ss-reset" title="Delete all screenshots for this actress and start over">Reset</button>`
    : '';
  _container.innerHTML = `
    <div class="av-ss-btn-row">
      <button class="av-ss-btn av-ss-generate">${label}</button>
      ${resetBtn}
    </div>`;
  _container.querySelector('.av-ss-generate').addEventListener('click', () => _enqueue());
  if (hasExisting) {
    _container.querySelector('.av-ss-reset').addEventListener('click', () => _resetActress());
  }
}

function _renderRunning(progress, streamGated) {
  const { done, total, inProgress } = progress;
  const pct = total > 0 ? Math.round((done / total) * 100) : 0;
  const subLabel = streamGated
    ? `<div class="av-ss-sublabel">paused — video playing</div>` : '';
  _container.innerHTML = `
    <div class="av-ss-progress-wrap">
      <div class="av-ss-bar-track">
        <div class="av-ss-bar-fill" style="width:${pct}%"></div>
      </div>
      <div class="av-ss-progress-label">${done}&thinsp;/&thinsp;${total}${inProgress > 0 ? ' …' : ''}</div>
      ${subLabel}
    </div>
    <div class="av-ss-btn-row">
      <button class="av-ss-action av-ss-pause">Pause</button>
      <button class="av-ss-action av-ss-stop">Stop</button>
    </div>`;
  _container.querySelector('.av-ss-pause').addEventListener('click', () => _pauseActress());
  _container.querySelector('.av-ss-stop').addEventListener('click', () => _stopActress());
}

function _renderPaused(progress) {
  const { done, total } = progress;
  const pct = total > 0 ? Math.round((done / total) * 100) : 0;
  _container.innerHTML = `
    <div class="av-ss-progress-wrap av-ss-progress-paused">
      <div class="av-ss-bar-track">
        <div class="av-ss-bar-fill av-ss-bar-paused" style="width:${pct}%"></div>
      </div>
      <div class="av-ss-progress-label">paused · ${done}&thinsp;/&thinsp;${total}</div>
    </div>
    <div class="av-ss-btn-row">
      <button class="av-ss-action av-ss-resume">Resume</button>
      <button class="av-ss-action av-ss-stop">Stop</button>
    </div>`;
  _container.querySelector('.av-ss-resume').addEventListener('click', () => _resumeActress());
  _container.querySelector('.av-ss-stop').addEventListener('click', () => _stopActress());
}

// ── Actions ────────────────────────────────────────────────────────────────

async function _enqueue() {
  const id = _mountId;
  try {
    const res = await fetch(
      `/api/av/actresses/${_actressId}/screenshots/enqueue`, { method: 'POST' });
    if (_mountId !== id || !_container) return;
    if (!res.ok) { console.error('[avstar-ss] enqueue failed', res.status); return; }
    await _fetchAndRender(id);
  } catch (e) {
    console.error('[avstar-ss] enqueue error', e);
  }
}

async function _pauseActress() {
  const id = _mountId;
  try {
    const res = await fetch(
      `/api/av/actresses/${_actressId}/screenshots/pause`, { method: 'POST' });
    if (_mountId !== id || !_container) return;
    if (!res.ok) { console.error('[avstar-ss] pause failed', res.status); return; }
    _fetchAndRender(id);
  } catch (e) {
    console.error('[avstar-ss] pause error', e);
  }
}

async function _resumeActress() {
  const id = _mountId;
  try {
    const res = await fetch(
      `/api/av/actresses/${_actressId}/screenshots/resume`, { method: 'POST' });
    if (_mountId !== id || !_container) return;
    if (!res.ok) { console.error('[avstar-ss] resume failed', res.status); return; }
    _fetchAndRender(id);
  } catch (e) {
    console.error('[avstar-ss] resume error', e);
  }
}

async function _resetActress() {
  if (!confirm('Delete all screenshots for this actress (DB rows, files, and any queued jobs) and start over?')) return;
  const id = _mountId;
  try {
    const res = await fetch(
      `/api/av/actresses/${_actressId}/screenshots`, { method: 'DELETE' });
    if (_mountId !== id || !_container) return;
    if (res.status === 409) {
      alert('Worker is currently generating for this actress. Pause/Stop and try again.');
      return;
    }
    if (!res.ok) { console.error('[avstar-ss] reset failed', res.status); return; }
    await _fetchPendingCount(id);
    await _fetchAndRender(id);
    window.dispatchEvent(new CustomEvent('av-screenshots-queue-done', {
      detail: { actressId: _actressId },
    }));
  } catch (e) {
    console.error('[avstar-ss] reset error', e);
  }
}

async function _stopActress() {
  if (!confirm('Remove all pending and paused screenshot jobs for this actress?')) return;
  const id = _mountId;
  try {
    const res = await fetch(
      `/api/av/actresses/${_actressId}/screenshots/queue`, { method: 'DELETE' });
    if (_mountId !== id || !_container) return;
    if (!res.ok) { console.error('[avstar-ss] stop failed', res.status); return; }
    clearInterval(_pollTimer);
    _pollTimer = null;
    _fetchAndRender(id);
  } catch (e) {
    console.error('[avstar-ss] stop error', e);
  }
}
