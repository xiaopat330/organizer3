import { esc } from './utils.js';

// ── State ─────────────────────────────────────────────────────────────────

function createState() {
  return {
    actresses: [],
    selectedId: null,
    activeTab: 'titles',
    queuePollTimer: null,
    paused: false,
  };
}

const state = createState();

// ── DOM refs ──────────────────────────────────────────────────────────────

const view              = document.getElementById('tools-javdb-discovery-view');
const queueBadge        = document.getElementById('jd-queue-badge');
const pauseBtn          = document.getElementById('jd-pause-btn');
const cancelAllBtn      = document.getElementById('jd-cancel-all-btn');
const actressList       = document.getElementById('jd-actress-list');
const emptyMsg          = document.getElementById('jd-empty');
const panel             = document.getElementById('jd-actress-panel');
const enrichBtn         = document.getElementById('jd-enrich-btn');
const cancelActressBtn  = document.getElementById('jd-cancel-actress-btn');
const subtabBtns        = panel?.querySelectorAll('.jd-subtab') ?? [];
const titlesView        = document.getElementById('jd-subview-titles');
const profileView       = document.getElementById('jd-subview-profile');
const conflictsView     = document.getElementById('jd-subview-conflicts');
const errorsView        = document.getElementById('jd-subview-errors');

// ── Public API ─────────────────────────────────────────────────────────────

export async function showJavdbDiscoveryView() {
  view.style.display = '';
  await Promise.all([loadActresses(), refreshQueue()]);
  startQueuePoll();
}

export function hideJavdbDiscoveryView() {
  view.style.display = 'none';
  stopQueuePoll();
}

// ── Data loading ───────────────────────────────────────────────────────────

async function loadActresses() {
  try {
    const res = await fetch('/api/javdb/discovery/actresses');
    if (!res.ok) return;
    state.actresses = await res.json();
    renderActressList();
  } catch (_) { /* network error — ignore */ }
}

async function refreshQueue() {
  try {
    const res = await fetch('/api/javdb/discovery/queue');
    if (!res.ok) return;
    const { pending, inFlight, failed, paused } = await res.json();
    state.paused = paused;
    const total = pending + inFlight + failed;
    if (total === 0) {
      queueBadge.style.display = 'none';
    } else {
      queueBadge.textContent = `${pending + inFlight} pending · ${failed} failed`;
      queueBadge.style.display = '';
    }
    pauseBtn.textContent = paused ? 'Resume' : 'Pause';
    pauseBtn.classList.toggle('jd-paused', paused);
  } catch (_) { /* ignore */ }
}

function startQueuePoll() {
  stopQueuePoll();
  state.queuePollTimer = setInterval(refreshQueue, 10_000);
}

function stopQueuePoll() {
  if (state.queuePollTimer !== null) {
    clearInterval(state.queuePollTimer);
    state.queuePollTimer = null;
  }
}

// ── Actress list rendering ─────────────────────────────────────────────────

function renderActressList() {
  actressList.innerHTML = '';
  for (const a of state.actresses) {
    const li = document.createElement('li');
    li.className = 'jd-actress-item';
    li.dataset.id = a.id;

    const enrichedPct = a.totalTitles > 0
      ? Math.round((a.enrichedTitles / a.totalTitles) * 100)
      : 0;

    const statusDot = actressStatusDot(a.actressStatus);

    li.innerHTML = `
      <span class="jd-actress-name">${statusDot}${esc(a.canonicalName)}</span>
      <span class="jd-actress-counts">${a.enrichedTitles}/${a.totalTitles} (${enrichedPct}%)</span>
    `;
    li.addEventListener('click', () => selectActress(a.id));
    actressList.appendChild(li);
  }

  if (state.selectedId !== null) {
    highlightSelected(state.selectedId);
  }
}

function actressStatusDot(status) {
  if (status === 'fetched')   return '<span class="jd-dot jd-dot-fetched" title="Profile fetched"></span>';
  if (status === 'slug_only') return '<span class="jd-dot jd-dot-slug" title="Slug only"></span>';
  return '<span class="jd-dot jd-dot-none" title="No staging row"></span>';
}

function highlightSelected(id) {
  actressList.querySelectorAll('.jd-actress-item').forEach(li => {
    li.classList.toggle('selected', Number(li.dataset.id) === id);
  });
}

// ── Actress selection ──────────────────────────────────────────────────────

async function selectActress(id) {
  state.selectedId = id;
  highlightSelected(id);
  emptyMsg.style.display = 'none';
  panel.style.display = '';
  await renderActiveTab();
}

async function renderActiveTab() {
  if (state.activeTab === 'titles') {
    await renderTitlesTab();
  } else if (state.activeTab === 'profile') {
    await renderProfileTab();
  } else if (state.activeTab === 'conflicts') {
    await renderConflictsTab();
  } else {
    await renderErrorsTab();
  }
}

// ── Titles tab ─────────────────────────────────────────────────────────────

async function renderTitlesTab() {
  titlesView.style.display    = '';
  profileView.style.display   = 'none';
  conflictsView.style.display = 'none';
  errorsView.style.display    = 'none';
  titlesView.innerHTML = '<div class="jd-loading">Loading…</div>';
  try {
    const res = await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/titles`);
    if (!res.ok) { titlesView.innerHTML = '<div class="jd-error">Failed to load titles.</div>'; return; }
    const titles = await res.json();
    if (titles.length === 0) {
      titlesView.innerHTML = '<div class="jd-empty-tab">No titles found.</div>';
      return;
    }
    titlesView.innerHTML = `
      <table class="jd-titles-table">
        <thead><tr>
          <th>Code</th><th>Status</th><th>Original Title</th><th>Release</th><th>Maker</th>
        </tr></thead>
        <tbody>${titles.map(titleRow).join('')}</tbody>
      </table>
    `;
  } catch (_) {
    titlesView.innerHTML = '<div class="jd-error">Network error.</div>';
  }
}

function titleRow(t) {
  const status = t.status
    ? `<span class="jd-status jd-status-${esc(t.status)}">${esc(t.status)}</span>`
    : '<span class="jd-status jd-status-none">—</span>';
  return `<tr>
    <td class="jd-code">${esc(t.code)}</td>
    <td>${status}</td>
    <td>${t.titleOriginal ? esc(t.titleOriginal) : '—'}</td>
    <td>${t.releaseDate ? esc(t.releaseDate) : '—'}</td>
    <td>${t.maker ? esc(t.maker) : '—'}</td>
  </tr>`;
}

// ── Profile tab ────────────────────────────────────────────────────────────

async function renderProfileTab() {
  profileView.style.display   = '';
  titlesView.style.display    = 'none';
  conflictsView.style.display = 'none';
  errorsView.style.display    = 'none';
  profileView.innerHTML = '<div class="jd-loading">Loading…</div>';
  try {
    const res = await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/profile`);
    if (res.status === 404) {
      profileView.innerHTML = '<div class="jd-empty-tab">No staging profile yet.</div>';
      return;
    }
    if (!res.ok) { profileView.innerHTML = '<div class="jd-error">Failed to load profile.</div>'; return; }
    const p = await res.json();
    profileView.innerHTML = `
      <div class="jd-profile">
        ${p.avatarUrl ? `<img class="jd-avatar" src="${esc(p.avatarUrl)}" alt="avatar">` : ''}
        <dl class="jd-profile-fields">
          <dt>Slug</dt><dd>${p.javdbSlug ? esc(p.javdbSlug) : '—'}</dd>
          <dt>Status</dt><dd><span class="jd-status jd-status-${esc(p.status ?? 'none')}">${esc(p.status ?? '—')}</span></dd>
          <dt>Fetched at</dt><dd>${p.rawFetchedAt ? esc(p.rawFetchedAt) : '—'}</dd>
          <dt>Title count</dt><dd>${p.titleCount != null ? p.titleCount : '—'}</dd>
          <dt>Twitter</dt><dd>${p.twitterHandle ? esc(p.twitterHandle) : '—'}</dd>
          <dt>Instagram</dt><dd>${p.instagramHandle ? esc(p.instagramHandle) : '—'}</dd>
          ${p.nameVariantsJson ? `<dt>Name variants</dt><dd class="jd-variants">${esc(p.nameVariantsJson)}</dd>` : ''}
        </dl>
      </div>
    `;
  } catch (_) {
    profileView.innerHTML = '<div class="jd-error">Network error.</div>';
  }
}

// ── Errors tab ─────────────────────────────────────────────────────────────

async function renderConflictsTab() {
  conflictsView.style.display = '';
  titlesView.style.display    = 'none';
  profileView.style.display   = 'none';
  errorsView.style.display    = 'none';
  conflictsView.innerHTML = '<div class="jd-loading">Loading…</div>';
  try {
    const res = await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/conflicts`);
    if (!res.ok) { conflictsView.innerHTML = '<div class="jd-error">Failed to load conflicts.</div>'; return; }
    const rows = await res.json();
    if (rows.length === 0) {
      conflictsView.innerHTML = '<div class="jd-empty-tab">No conflicts — javdb cast matches for all enriched titles.</div>';
      return;
    }
    conflictsView.innerHTML = `
      <table class="jd-titles-table jd-conflicts-table">
        <thead><tr>
          <th>Code</th><th>Our Attribution</th><th>javdb Cast</th>
        </tr></thead>
        <tbody>${rows.map(conflictRow).join('')}</tbody>
      </table>
    `;
  } catch (_) {
    conflictsView.innerHTML = '<div class="jd-error">Network error.</div>';
  }
}

function conflictRow(r) {
  const cast = parseCast(r.castJson);
  const castNames = cast.length > 0
    ? cast.map(e => esc(e.name)).join(', ')
    : '<span class="jd-muted">—</span>';
  return `<tr>
    <td class="jd-code">${esc(r.code)}</td>
    <td>${esc(r.ourActressName)}</td>
    <td class="jd-conflict-cast">${castNames}</td>
  </tr>`;
}

function parseCast(castJson) {
  if (!castJson) return [];
  try { return JSON.parse(castJson); } catch (_) { return []; }
}

async function renderErrorsTab() {
  errorsView.style.display    = '';
  titlesView.style.display    = 'none';
  profileView.style.display   = 'none';
  conflictsView.style.display = 'none';
  errorsView.innerHTML = '<div class="jd-loading">Loading…</div>';
  try {
    const res = await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/errors`);
    if (!res.ok) { errorsView.innerHTML = '<div class="jd-error">Failed to load errors.</div>'; return; }
    const jobs = await res.json();
    if (jobs.length === 0) {
      errorsView.innerHTML = '<div class="jd-empty-tab">No failed jobs.</div>';
      return;
    }
    errorsView.innerHTML = '';
    const retryAllRow = document.createElement('div');
    retryAllRow.className = 'jd-errors-actions';
    const retryAllBtn = document.createElement('button');
    retryAllBtn.type = 'button';
    retryAllBtn.className = 'jd-action-btn jd-retry-btn';
    retryAllBtn.textContent = `Retry All (${jobs.length})`;
    retryAllBtn.addEventListener('click', () => retryActress());
    retryAllRow.appendChild(retryAllBtn);
    errorsView.appendChild(retryAllRow);

    const list = document.createElement('ul');
    list.className = 'jd-errors-list';
    for (const job of jobs) {
      const li = document.createElement('li');
      li.className = 'jd-error-row';
      li.innerHTML = `
        <span class="jd-error-type">${esc(job.jobType)}</span>
        <span class="jd-error-msg">${job.lastError ? esc(job.lastError) : '(no message)'}</span>
        <span class="jd-error-attempts">${job.attempts} attempt${job.attempts !== 1 ? 's' : ''}</span>
      `;
      list.appendChild(li);
    }
    errorsView.appendChild(list);
  } catch (_) {
    errorsView.innerHTML = '<div class="jd-error">Network error.</div>';
  }
}

// ── M3 actions ─────────────────────────────────────────────────────────────

async function enrichActress() {
  if (state.selectedId === null) return;
  enrichBtn.disabled = true;
  try {
    await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/enqueue`, { method: 'POST' });
    await Promise.all([loadActresses(), refreshQueue()]);
  } finally {
    enrichBtn.disabled = false;
  }
}

async function cancelActress() {
  if (state.selectedId === null) return;
  await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/queue`, { method: 'DELETE' });
  await refreshQueue();
}

async function cancelAll() {
  if (!window.confirm('Cancel all pending javdb enrichment jobs?')) return;
  await fetch('/api/javdb/discovery/queue', { method: 'DELETE' });
  await refreshQueue();
}

async function togglePause() {
  const newPaused = !state.paused;
  await fetch('/api/javdb/discovery/queue/pause', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ paused: newPaused }),
  });
  await refreshQueue();
}

async function retryActress() {
  if (state.selectedId === null) return;
  await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/retry`, { method: 'POST' });
  await Promise.all([refreshQueue(), renderErrorsTab()]);
}

// ── Button wiring ──────────────────────────────────────────────────────────

pauseBtn.addEventListener('click', togglePause);
cancelAllBtn.addEventListener('click', cancelAll);
enrichBtn.addEventListener('click', enrichActress);
cancelActressBtn.addEventListener('click', cancelActress);

subtabBtns.forEach(btn => {
  btn.addEventListener('click', async () => {
    subtabBtns.forEach(b => b.classList.remove('selected'));
    btn.classList.add('selected');
    state.activeTab = btn.dataset.tab;
    if (state.selectedId !== null) await renderActiveTab();
  });
});
