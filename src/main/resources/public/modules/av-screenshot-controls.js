// av-screenshot-controls.js
// Shared UI module for AV screenshot generation queue controls.
// API: mount(containerEl, actressId, pendingVideoCount) / unmount()
//
// States (spec §5.1):
//   all-done  — "Screenshots ✓" disabled
//   idle      — "Generate screenshots (N pending)"
//   running   — progress bar + Pause + Stop
//   paused    — progress bar (paused style) + Resume + Stop
//   (sub-state) streamGated — running state + "paused — video playing" sub-label

const POLL_MS = 2000;

let _container = null;
let _actressId = null;
let _pendingVideoCount = null;
let _pollTimer = null;
let _mountId = 0;

// mount() may be called repeatedly when the user selects a different actress.
// It always fully unmounts the prior instance before setting up the new one.
//
// pendingVideoCount: number of this actress's videos lacking screenshots. Pass it when the
// host already has the data (the profile screen does — `allVideos`). Pass null and the
// module will fetch /videos itself on first render. Required to drive the "Screenshots ✓"
// terminal state and the "(N pending)" idle label.
export function mount(containerEl, actressId, pendingVideoCount = null) {
  unmount();
  _container = containerEl;
  _actressId = actressId;
  _pendingVideoCount = pendingVideoCount;
  const id = ++_mountId;
  if (_pendingVideoCount === null) {
    fetchPendingCount(id).then(() => fetchAndRender(id));
  } else {
    fetchAndRender(id);
  }
}

async function fetchPendingCount(id) {
  try {
    const res = await fetch(`/api/av/actresses/${_actressId}/videos`);
    if (_mountId !== id || !_container) return;
    if (!res.ok) return;
    const videos = await res.json();
    if (_mountId !== id || !_container) return;
    _pendingVideoCount = videos.filter(v => !v.screenshotCount).length;
  } catch { /* leave _pendingVideoCount null; idle label degrades gracefully */ }
}

// Safe to call when not mounted.
export function unmount() {
  if (_pollTimer !== null) {
    clearInterval(_pollTimer);
    _pollTimer = null;
  }
  _container = null;
  _actressId = null;
  _pendingVideoCount = null;
}

// ── Fetch / poll ───────────────────────────────────────────────────────────

async function fetchAndRender(id) {
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
    render(progress, worker);
    if (isActive(progress) && _pollTimer === null) {
      _pollTimer = setInterval(() => tick(id), POLL_MS);
    }
  } catch { /* network error; retried on next tick if polling */ }
}

async function tick(id) {
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
    render(progress, worker);
    if (!isActive(progress)) {
      clearInterval(_pollTimer);
      _pollTimer = null;
      if (progress.pending + progress.inProgress + progress.paused === 0) {
        // Work fully drained (not merely paused): notify host to refresh the video grid.
        // Event name is intentionally distinct from the existing per-video
        // `av-screenshots-generated` (and dispatched on `window`, not `document`) to avoid
        // payload-shape collisions. See spec/PROPOSAL_AV_SCREENSHOT_QUEUE.md §5.2.
        window.dispatchEvent(new CustomEvent('av-screenshots-queue-done', {
          detail: { actressId: _actressId },
        }));
      }
    }
  } catch { /* ignore; next tick retries */ }
}

function isActive(p) {
  return p.pending + p.inProgress + p.paused > 0;
}

// ── State renderers ────────────────────────────────────────────────────────

function render(progress, worker) {
  if (!_container) return;
  const { pending, inProgress, paused, done, total, currentVideoId } = progress;

  if (pending + inProgress > 0) {
    const streamGated = worker.streamActive
      && currentVideoId != null
      && worker.currentVideoId === currentVideoId;
    renderRunning(progress, streamGated);
    return;
  }

  if (paused > 0) {
    renderPaused(progress);
    return;
  }

  // Idle: no active queue rows.
  const allDone = (total > 0 && done === total) || _pendingVideoCount === 0;
  if (allDone) {
    renderAllDone();
  } else {
    renderIdle();
  }
}

function renderAllDone() {
  _container.innerHTML =
    `<button class="av-ss-btn av-ss-done" disabled>Screenshots ✓</button>`;
}

function renderIdle() {
  const label = _pendingVideoCount != null
    ? `Generate screenshots (${_pendingVideoCount} pending)`
    : 'Generate screenshots';
  _container.innerHTML =
    `<button class="av-ss-btn av-ss-generate">${label}</button>`;
  _container.querySelector('.av-ss-generate')
    .addEventListener('click', () => enqueue());
}

function renderRunning(progress, streamGated) {
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
  _container.querySelector('.av-ss-pause').addEventListener('click', () => pauseActress());
  _container.querySelector('.av-ss-stop').addEventListener('click', () => stopActress());
}

function renderPaused(progress) {
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
  _container.querySelector('.av-ss-resume').addEventListener('click', () => resumeActress());
  _container.querySelector('.av-ss-stop').addEventListener('click', () => stopActress());
}

// ── Actions ────────────────────────────────────────────────────────────────

async function enqueue() {
  const id = _mountId;
  try {
    const res = await fetch(
      `/api/av/actresses/${_actressId}/screenshots/enqueue`, { method: 'POST' });
    if (_mountId !== id || !_container) return;
    if (!res.ok) { console.error('av-screenshot-controls: enqueue failed', res.status); return; }
    await fetchAndRender(id);
  } catch (e) {
    console.error('av-screenshot-controls: enqueue error', e);
  }
}

async function pauseActress() {
  const id = _mountId;
  try {
    const res = await fetch(
      `/api/av/actresses/${_actressId}/screenshots/pause`, { method: 'POST' });
    if (_mountId !== id || !_container) return;
    if (!res.ok) { console.error('av-screenshot-controls: pause failed', res.status); return; }
    fetchAndRender(id);
  } catch (e) {
    console.error('av-screenshot-controls: pause error', e);
  }
}

async function resumeActress() {
  const id = _mountId;
  try {
    const res = await fetch(
      `/api/av/actresses/${_actressId}/screenshots/resume`, { method: 'POST' });
    if (_mountId !== id || !_container) return;
    if (!res.ok) { console.error('av-screenshot-controls: resume failed', res.status); return; }
    fetchAndRender(id);
  } catch (e) {
    console.error('av-screenshot-controls: resume error', e);
  }
}

async function stopActress() {
  if (!confirm('Remove all pending and paused screenshot jobs for this actress?')) return;
  const id = _mountId;
  try {
    const res = await fetch(
      `/api/av/actresses/${_actressId}/screenshots/queue`, { method: 'DELETE' });
    if (_mountId !== id || !_container) return;
    if (!res.ok) { console.error('av-screenshot-controls: stop failed', res.status); return; }
    clearInterval(_pollTimer);
    _pollTimer = null;
    fetchAndRender(id);
  } catch (e) {
    console.error('av-screenshot-controls: stop error', e);
  }
}
